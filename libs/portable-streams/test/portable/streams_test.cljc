(ns portable.streams-test
  (:require [portable.bytes :as bytes]
            [portable.bytes.operation :as operation]
            [portable.streams :as streams]
            [portable.streams.internal :as internal]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is async]])))

(defn- run-contract! [scenario]
  #?(:clj (let [done (promise)] (scenario #(deliver done true)) (is (true? (deref done 10000 false))))
     :cljs (async done (scenario done))))

(defn- read-all! [source size received callback]
  (streams/read! source {:max-bytes size}
                 (fn [error data]
                   (is (nil? error))
                   (when data (is (bytes/byte-vector? data)) (is (<= 1 (count data) size)))
                   (if (and (nil? error) data)
                     (read-all! source size (into received data) callback)
                     (callback received)))))

(defn- reason [f & args]
  (try (apply f args) (catch #?(:clj Exception :cljs :default) error (:reason (ex-data error)))))

(deftest finite-sources-binary-empty-eof-and-async-callbacks
  (run-contract!
   (fn [done]
     (let [source (streams/open-source! (vec (range 256)))
           returned (atom false)
           #?@(:clj [caller (Thread/currentThread)])]
       (is (nil? (streams/read!
                  source {:max-bytes 7}
                  (fn [error data]
                    #?(:clj (is (not (identical? caller (Thread/currentThread)))) :cljs (is @returned))
                    (is (nil? error)) (is (= (vec (range 7)) data))
                    (read-all!
                     source 11 data
                     (fn [all]
                       (is (= (vec (range 256)) all))
                       (streams/read! source
                                      (fn [error value]
                                        (is (nil? error)) (is (nil? value))
                                        (streams/close! source)
                                        (streams/read! source (fn [error value]
                                                                (is (= :closed (:reason (ex-data error))))
                                                                (is (nil? value))
                                                                (streams/read! (streams/open-source! [])
                                                                               (fn [error value]
                                                                                 (is (nil? error)) (is (nil? value))
                                                                                 (done)))))))))))))
       (reset! returned true)))))

(deftest memory-pipe-backpressure-chunking-finish-and-eof
  (run-contract!
   (fn [done]
     (let [{:keys [source sink]} (streams/open-pipe! {:capacity 3})
           payload (vec (range 64))
           completed (atom 0)
           finish! #(when (= 2 (swap! completed inc)) (streams/close! source) (done))]
       (is (nil? (streams/write! sink payload
                                (fn [error count]
                                  (is (nil? error)) (is (= 64 count))
                                  (streams/finish! sink
                                                   (fn [error value]
                                                     (is (nil? error)) (is (nil? value))
                                                     (streams/finish! sink
                                                                      (fn [error value]
                                                                        (is (nil? error)) (is (nil? value))
                                                                        (streams/write! sink []
                                                                                        (fn [error value]
                                                                                          (is (= :output-closed (:reason (ex-data error))))
                                                                                          (is (nil? value)) (finish!)))))))))))
       (read-all! source 2 [] (fn [data] (is (= payload data)) (finish!)))))))

(deftest finish-drains-buffer-and-empty-writes-are-not-eof
  (run-contract!
   (fn [done]
     (let [{:keys [source sink]} (streams/open-pipe! {:capacity 4})]
       (streams/write! sink []
                       (fn [error count]
                         (is (nil? error)) (is (= 0 count))
                         (streams/write! sink [0 255 1]
                                         (fn [error count]
                                           (is (nil? error)) (is (= 3 count))
                                           (streams/finish! sink
                                                            (fn [error _]
                                                              (is (nil? error))
                                                              (read-all! source 2 []
                                                                         (fn [data]
                                                                           (is (= [0 255 1] data))
                                                                           (streams/close! sink) (done)))))))))))))

(deftest busy-operations-do-not-replace-pending-work
  (run-contract!
   (fn [done]
     (let [{:keys [source]} (streams/open-pipe! {:capacity 1})
           completed (atom 0)
           next! #(when (= 5 (swap! completed inc)) (done))]
       (streams/read! source (fn [error value] (is (= :closed (:reason (ex-data error)))) (is (nil? value)) (next!)))
       (streams/read! source (fn [error _] (is (= :busy (:reason (ex-data error)))) (next!)))
       (streams/close! source)
       (let [{:keys [source sink]} (streams/open-pipe! {:capacity 1})]
         (streams/write! sink [1 2]
                         (fn [error value] (is (= :closed (:reason (ex-data error)))) (is (nil? value)) (next!)))
         (streams/write! sink [] (fn [error _] (is (= :busy (:reason (ex-data error)))) (next!)))
         (streams/finish! sink (fn [error _] (is (= :busy (:reason (ex-data error))))
                                 (streams/close! source) (next!))))))))

(deftest close-aborts-both-ends-including-idle-eof-and-finished-peers
  (run-contract!
   (fn [done]
     (let [{:keys [source sink]} (streams/open-pipe!)]
       (streams/finish! sink
                        (fn [error _]
                          (is (nil? error))
                          (streams/read! source
                                         (fn [error value]
                                           (is (nil? error)) (is (nil? value))
                                           (is (nil? (streams/close! sink))) (is (nil? (streams/close! source)))
                                           (let [completed (atom 0) next! #(when (= 3 (swap! completed inc)) (done))]
                                             (streams/read! source (fn [error _] (is (= :closed (:reason (ex-data error)))) (next!)))
                                             (streams/write! sink [] (fn [error _] (is (= :closed (:reason (ex-data error)))) (next!)))
                                             (streams/finish! sink (fn [error _] (is (= :closed (:reason (ex-data error)))) (next!))))))))))))

(deftest bounded-pipe-copies-and-finishes-output
  (run-contract!
   (fn [done]
     (let [payload (vec (take 4096 (cycle (range 251))))
           input (streams/open-source! payload)
           {:keys [source sink]} (streams/open-pipe! {:capacity 13})
           completed (atom 0)
           next! #(when (= 2 (swap! completed inc)) (streams/close! input) (streams/close! sink) (done))]
       (streams/pipe! input sink {:chunk-bytes 31 :max-bytes (count payload)}
                      (fn [error count]
                        (is (nil? error)) (is (= (clojure.core/count payload) count))
                        (streams/read! input (fn [error value] (is (nil? error)) (is (nil? value)) (next!)))))
       (read-all! source 7 [] (fn [result] (is (= payload result)) (next!)))))))

(deftest pipe-can-leave-output-open
  (run-contract!
   (fn [done]
     (let [input (streams/open-source! [1]) {:keys [source sink]} (streams/open-pipe!)]
       (streams/pipe! input sink {:finish? false}
                      (fn [error count]
                        (is (nil? error)) (is (= 1 count))
                        (streams/write! sink [2]
                                        (fn [error count]
                                          (is (nil? error)) (is (= 1 count))
                                          (streams/finish! sink
                                                           (fn [error _]
                                                             (is (nil? error))
                                                             (read-all! source 10 []
                                                                        (fn [data] (is (= [1 2] data)) (done)))))))))))))

(deftest pipe-observes-backpressure-and-exclusive-endpoint-ownership
  (run-contract!
   (fn [done]
     (let [reads (atom 0) writes (atom 0) pending-write (atom nil)
           input (internal/create-endpoint!
                  :source
                  (fn [_ options _ complete!]
                    (is (= 4 (:max-bytes options)))
                    (case (swap! reads inc) 1 (complete! nil [1 2 3 4]) (complete! nil nil)))
                  (fn []) #?(:clj (Object.) :cljs (js-obj)))
           output (internal/create-endpoint!
                   :sink
                   (fn [action _ data complete!]
                     (case action
                       :write (do (swap! writes inc) (is (= [1 2 3 4] data)) (reset! pending-write complete!))
                       :finish (complete! nil nil)))
                   (fn []) #?(:clj (Object.) :cljs (js-obj)))
           completed (atom 0)
           next! #(when (= 3 (swap! completed inc)) (done))]
       (streams/pipe! input output {:chunk-bytes 4}
                      (fn [error count]
                        (is (nil? error)) (is (= 4 count))
                        (is (= 2 @reads)) (is (= 1 @writes)) (next!)))
       (streams/read! input (fn [error _] (is (= :busy (:reason (ex-data error)))) (next!)))
       (letfn [(check! []
                 (if-let [complete! @pending-write]
                   (do (is (= 1 @reads))
                       (streams/write! output []
                                       (fn [error _]
                                         (is (= :busy (:reason (ex-data error))))
                                         (complete! nil 4) (next!))))
                   (operation/dispatch! check!)))]
         (check!))))))

(deftest pipe-cancellation-and-limit-errors-abort-owned-ends
  (run-contract!
   (fn [done]
     (letfn [(check! [cases]
               (if-let [[cancel? data limit] (first cases)]
                 (let [input (streams/open-source! data)
                       {:keys [source sink]} (streams/open-pipe! {:capacity 1})
                       calls (atom 0)
                       cancel! (streams/pipe!
                                input sink {:max-bytes limit :chunk-bytes 2}
                                (fn [error value]
                                  (is (= 1 (swap! calls inc)))
                                  (is (= (if cancel? :cancelled :byte-limit-exceeded) (:reason (ex-data error))))
                                  (is (nil? value))
                                  (streams/read! input
                                                 (fn [error _]
                                                   (is (= :closed (:reason (ex-data error))))
                                                   (streams/write! sink []
                                                                   (fn [error _]
                                                                     (is (= :closed (:reason (ex-data error))))
                                                                     (check! (next cases))))))))]
                   (when cancel? (is (nil? (cancel!))) (is (nil? (cancel!))))
                   (when-not cancel?
                     (letfn [(drain! [] (streams/read! source
                                                       (fn [error data] (when (and (nil? error) data) (drain!)))))]
                       (drain!))))
                 (done)))]
       (check! [[true [1 2 3 4] 100] [false [1] 0] [false [1 2] 1]])))))

(deftest failed-pipe-claim-preserves-other-streams
  (run-contract!
   (fn [done]
     (let [input (streams/open-source! [1]) {:keys [source sink]} (streams/open-pipe! {:capacity 1})]
       (streams/write! sink [2 3]
                       (fn [error count] (is (nil? error)) (is (= 2 count))
                         (streams/finish! sink (fn [error _] (is (nil? error))))))
       (streams/pipe! input sink
                      (fn [error value]
                        (is (= :busy (:reason (ex-data error)))) (is (nil? value))
                        (streams/read! input
                                       (fn [error data]
                                         (is (nil? error)) (is (= [1] data))
                                         (read-all! source 2 []
                                                    (fn [data] (is (= [2 3] data)) (done)))))))))))

(deftest operation-completion-is-once-and-callback-errors-escape
  (let [tasks (atom []) calls (atom 0)
        failure #?(:clj (Exception. "Callback failed") :cljs (js/Error. "Callback failed"))
        input (internal/create-endpoint! :source
                                         (fn [_ _ _ complete!]
                                           (complete! nil [1]) (complete! failure nil) (throw failure))
                                         (fn []) #?(:clj (Object.) :cljs (js-obj)))]
    (with-redefs [operation/dispatch! (fn [task] (swap! tasks conj task))]
      (streams/read! input (fn [error value]
                             (is (nil? error)) (is (= [1] value)) (swap! calls inc) (throw failure)))
      ((first @tasks))
      (is (= 2 (count @tasks)))
      (is (identical? failure (try ((second @tasks)) (catch #?(:clj Exception :cljs :default) error error))))
      (is (= 1 @calls)))))

(deftest validation-before-state-changes
  (let [{:keys [source sink]} (streams/open-pipe!)]
    (doseq [[f args expected]
            [[streams/open-pipe! [nil] :expected-map]
             [streams/open-pipe! [{:capacity 0}] :out-of-range]
             [streams/open-pipe! [{:capacity 1048577}] :out-of-range]
             [streams/open-pipe! [{:typo true}] :unknown-option]
             [streams/open-source! [nil] :expected-byte-vector]
             [streams/open-source! [[-1]] :expected-byte-vector]
             [streams/read! [nil (fn [_ _])] :invalid-stream]
             [streams/read! [sink (fn [_ _])] :wrong-direction]
             [streams/read! [source {:max-bytes 0} (fn [_ _])] :out-of-range]
             [streams/read! [source nil] :expected-callback]
             [streams/write! [source [] (fn [_ _])] :wrong-direction]
             [streams/write! [sink [256] (fn [_ _])] :expected-byte-vector]
             [streams/write! [sink (vec (repeat 1048577 0)) (fn [_ _])] :input-too-large]
             [streams/finish! [source (fn [_ _])] :wrong-direction]
             [streams/close! [{}] :invalid-stream]
             [streams/pipe! [source sink (fn [_ _])] :same-stream]
             [streams/pipe! [source sink {:max-bytes -1} (fn [_ _])] :out-of-range]
             [streams/pipe! [source sink {:max-bytes 9007199254740992} (fn [_ _])] :out-of-range]
             [streams/pipe! [source sink {:chunk-bytes 0} (fn [_ _])] :out-of-range]
             [streams/pipe! [source sink {:finish? nil} (fn [_ _])] :expected-boolean]]]
      (is (= expected (apply reason f args))))
    (streams/close! source)))

(deftest invalid-adapter-results-close-once-and-settle-once
  (run-contract!
   (fn [done]
     (letfn [(check! [cases]
               (if-let [[direction action value] (first cases)]
                 (let [aborts (atom 0) calls (atom 0)
                       endpoint (internal/create-endpoint!
                                 direction (fn [_ _ _ complete!]
                                             (complete! nil value) (complete! nil value))
                                 #(swap! aborts inc) #?(:clj (Object.) :cljs (js-obj)))
                       callback (fn [error result]
                                  (is (= 1 (swap! calls inc)))
                                  (is (= :portable/stream-error (:type (ex-data error))))
                                  (is (= :invalid-result (:reason (ex-data error))))
                                  (is (nil? result))
                                  (streams/close! endpoint)
                                  (is (= 1 @aborts)) (check! (next cases)))]
                   (case action
                     :read (streams/read! endpoint {:max-bytes 1} callback)
                     :write (streams/write! endpoint [1 2] callback)
                     :finish (streams/finish! endpoint callback)))
                 (done)))]
       (check! [[:source :read []] [:source :read [-1]] [:source :read [1 2]]
                [:sink :write 1] [:sink :finish 0]])))))

(deftest pipe-provider-failure-retains-cause-and-aborts-both-ends
  (run-contract!
   (fn [done]
     (let [failure #?(:clj (Exception. "Read failed") :cljs (js/Error. "Read failed"))
           reads (atom 0) source-aborts (atom 0) sink-aborts (atom 0) received (atom [])
           input (internal/create-endpoint!
                  :source (fn [_ _ _ complete!]
                            (if (= 1 (swap! reads inc)) (complete! nil [1]) (throw failure)))
                  #(swap! source-aborts inc) #?(:clj (Object.) :cljs (js-obj)))
           output (internal/create-endpoint!
                   :sink (fn [_ _ data complete!] (swap! received into data) (complete! nil (count data)))
                   #(swap! sink-aborts inc) #?(:clj (Object.) :cljs (js-obj)))]
       (streams/pipe! input output
                      (fn [error result]
                        (is (= :portable.streams/pipe! (:operation (ex-data error))))
                        (is (= :io-error (:reason (ex-data error)))) (is (nil? result))
                        (is (identical? failure (ex-cause (ex-cause error))))
                        (is (= [1] @received)) (is (= 1 @source-aborts)) (is (= 1 @sink-aborts)) (done)))))))

(deftest cancel-blocked-write-and-ignore-late-provider-completion
  (run-contract!
   (fn [done]
     (let [pending (atom nil) cancel (atom nil) callbacks (atom 0) aborts (atom 0)
           input (streams/open-source! [1 2 3])
           output (internal/create-endpoint!
                   :sink (fn [_ _ _ complete!] (reset! pending complete!))
                   #(swap! aborts inc) #?(:clj (Object.) :cljs (js-obj)))]
       (reset! cancel
               (streams/pipe!
                input output
                (fn [error value]
                  (is (= 1 (swap! callbacks inc)))
                  (is (= :cancelled (:reason (ex-data error)))) (is (nil? value))
                  (is (= 1 @aborts))
                  (@pending nil 3)
                  (is (nil? (@cancel)))
                  (streams/read! input (fn [error _] (is (= :closed (:reason (ex-data error)))) (done))))))
       (letfn [(cancel-when-blocked! []
                 (if @pending (@cancel) (operation/dispatch! cancel-when-blocked!)))]
         (cancel-when-blocked!))))))

(deftest large-transfer-is-chunked-beyond-one-write-limit
  (run-contract!
   (fn [done]
     (let [payload (vec (repeat 1048589 255)) input (streams/open-source! payload)
           {:keys [source sink]} (streams/open-pipe! {:capacity 8192})
           completed (atom 0) next! #(when (= 2 (swap! completed inc)) (done))]
       (streams/pipe! input sink {:chunk-bytes 65536}
                      (fn [error count] (is (nil? error)) (is (= (clojure.core/count payload) count)) (next!)))
       (read-all! source 8192 [] (fn [result] (is (= payload result)) (next!)))))))

(deftest simultaneous-pipes-keep-independent-byte-order
  (run-contract!
   (fn [done]
     (let [completed (atom 0)]
       (doseq [index (range 8)]
         (let [payload (vec (repeat 64 index)) input (streams/open-source! payload)
               {:keys [source sink]} (streams/open-pipe! {:capacity 3})
               events (atom 0)
               next! #(when (= 2 (swap! events inc))
                        (streams/close! input) (streams/close! source)
                        (when (= 8 (swap! completed inc)) (done)))]
           (streams/pipe! input sink {:chunk-bytes 7}
                          (fn [error count] (is (nil? error)) (is (= 64 count)) (next!)))
           (read-all! source 2 [] (fn [data] (is (= payload data)) (next!)))))))))

(deftest full-buffer-retains-the-write-until-readers-free-capacity
  (run-contract!
   (fn [done]
     (let [{:keys [source sink]} (streams/open-pipe! {:capacity 2})
           tasks (atom []) accepted (atom []) first-byte (atom nil)
           run-next! (fn [] (let [task (first @tasks)] (swap! tasks #(subvec % 1)) (task)))]
       (with-redefs [operation/dispatch! (fn [task] (swap! tasks conj task))]
         (streams/write! sink [1 2 3 4]
                         (fn [error count]
                           (is (nil? error)) (swap! accepted conj count)
                           (streams/finish! sink (fn [error _] (is (nil? error))))))
         (run-next!)
         (is (empty? @accepted))
         (streams/read! source {:max-bytes 1} (fn [error value] (is (nil? error)) (reset! first-byte value)))
         (run-next!)
         (is (empty? @accepted))
         (while (seq @tasks) (run-next!))
         (is (= [1] @first-byte)))
       (read-all! source 1 []
                  (fn [remaining]
                    (is (= [2 3 4] remaining)) (is (= [4] @accepted)) (done)))))))

(deftest exact-and-empty-byte-limits-and-cancel-after-success
  (run-contract!
   (fn [done]
     (letfn [(check! [examples]
               (if-let [data (first examples)]
                 (let [input (streams/open-source! data) {:keys [source sink]} (streams/open-pipe!)
                       cancel (atom nil)]
                   (reset!
                    cancel
                    (streams/pipe!
                     input sink {:max-bytes (count data)}
                     (fn [error count]
                       (is (nil? error)) (is (= (clojure.core/count data) count))
                       (letfn [(verify! []
                                 (if-let [cancel! @cancel]
                                   (do
                                     (is (nil? (cancel!))) (is (nil? (cancel!)))
                                     (streams/write!
                                      sink []
                                      (fn [error _]
                                        (is (= :output-closed (:reason (ex-data error))))
                                        (streams/read!
                                         input
                                         (fn [error value]
                                           (is (nil? error)) (is (nil? value))
                                           (read-all! source 1 []
                                                      (fn [received]
                                                        (is (= data received))
                                                        (streams/close! sink)
                                                        (check! (next examples)))))))))
                                   (operation/dispatch! verify!)))]
                         (verify!))))))
                 (done)))]
       (check! [[] [1] [1 2]])))))

(deftest progressing-pipes-yield-for-external-cancellation
  (run-contract!
   (fn [done]
     (let [reads (atom 0)
           input (internal/create-endpoint!
                  :source (fn [_ _ _ complete!] (swap! reads inc) (complete! nil [1]))
                  (fn []) #?(:clj (Object.) :cljs (js-obj)))
           output (internal/create-endpoint!
                   :sink (fn [_ _ data complete!] (complete! nil (count data)))
                   (fn []) #?(:clj (Object.) :cljs (js-obj)))
           cancel! (streams/pipe!
                    input output {:max-bytes 10000}
                    (fn [error count]
                      (is (= :cancelled (:reason (ex-data error)))) (is (nil? count))
                      (is (< @reads 10000)) (done)))]
       #?(:clj (operation/dispatch! cancel!) :cljs (js/setTimeout cancel! 0))))))

(deftest closing-after-eof-preserves-committed-finish
  (run-contract!
   (fn [done]
     (let [{:keys [source sink]} (streams/open-pipe!)
           completed (atom 0) next! #(when (= 2 (swap! completed inc)) (done))]
       (streams/read! source (fn [error value]
                               (is (nil? error)) (is (nil? value))
                               (streams/close! source) (next!)))
       (streams/finish! sink (fn [error value] (is (nil? error)) (is (nil? value)) (next!)))))))

#?(:clj (def ^:dynamic *context* :none))

#?(:clj
   (deftest callback-bindings-follow-the-operation-creator
     (run-contract!
      (fn [done]
        (let [{:keys [source sink]} (streams/open-pipe! {:capacity 1})
              events (atom 0) next! #(when (= 2 (swap! events inc)) (done))]
          (binding [*context* :reader]
            (streams/read! source
                           (fn [error data]
                             (is (nil? error)) (is (= [1] data)) (is (= :reader *context*)) (next!))))
          (binding [*context* :writer]
            (streams/write! sink [1]
                            (fn [error count]
                              (is (nil? error)) (is (= 1 count)) (is (= :writer *context*)) (next!)))))))))

#?(:clj
   (deftest pipe-cancellation-retains-the-creator-bindings
     (run-contract!
      (fn [done]
        (let [input (streams/open-source! [1 2 3]) {:keys [sink]} (streams/open-pipe! {:capacity 1})
              cancel! (binding [*context* :creator]
                        (streams/pipe! input sink
                                       (fn [error _]
                                         (is (= :cancelled (:reason (ex-data error))))
                                         (is (= :creator *context*)) (done))))]
          (binding [*context* :canceller] (cancel!)))))))
