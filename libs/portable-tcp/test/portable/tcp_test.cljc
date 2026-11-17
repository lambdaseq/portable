(ns portable.tcp-test
  (:require [portable.tcp :as tcp]
            [portable.tcp-fixture :as fixture]
            [portable.tcp.internal :as internal]
            #?(:clj [clojure.test :as test :refer [deftest is]]
               :cljs [cljs.test :as test :refer-macros [deftest is async]])))

(defn- is-browser? [] #?(:clj false :cljs (exists? js/window)))

(defn- get-error-data [f & args]
  (try (apply f args)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error (ex-data error))))

(defn- run-contract! [scenario]
  #?(:clj (let [finished (promise)]
            (scenario #(deliver finished :done))
            (is (= :done (deref finished 10000 :timeout))))
     :cljs (async done (scenario done))))

(defn- with-connection! [mode scenario done]
  (fixture/start!
   mode
   (fn [{:keys [port stop!]}]
     (let [connection (atom nil)
           finished (atom false)
           cancel-watchdog (atom nil)
           finish! (fn []
                     (when (compare-and-set! finished false true)
                       (when-let [cancel @cancel-watchdog] (cancel))
                       (when-let [handle @connection] (tcp/close! handle))
                       (stop! done)))]
       (reset! cancel-watchdog
               (fixture/after! 5000 #(do (is false "Loopback contract timed out.") (finish!))))
       (let [handle (tcp/connect!
                     {:host "127.0.0.1" :port port :timeout-ms 1000}
                     (fn [error handle]
                       (is (nil? error))
                       (if error
                         (finish!)
                         (scenario handle finish!))))]
         (reset! connection handle))))))

(defn- read-all! [connection limit result done]
  (tcp/read! connection {:max-bytes limit :timeout-ms 1000}
             (fn [error chunk]
               (is (nil? error))
               (when chunk
                 (is (vector? chunk))
                 (is (<= 1 (count chunk) limit)))
               (cond
                 error (done result)
                 (nil? chunk) (done result)
                 :else (read-all! connection limit (into result chunk) done)))))

(deftest fragmented-read-and-eof-contract
  (when-not (is-browser?)
    (run-contract!
     #(with-connection!
       :payload
       (fn [connection finish!]
         (read-all! connection 7 []
                    (fn [data]
                      (is (= fixture/PAYLOAD data))
                      (tcp/read! connection
                                 (fn [error chunk]
                                   (is (nil? error)) (is (nil? chunk)) (finish!))))))
       %))))

(deftest write-half-close-and-echo-contract
  (when-not (is-browser?)
    (run-contract!
     #(with-connection!
       :echo
       (fn [connection finish!]
         (tcp/write! connection []
                     (fn [error count]
                       (is (nil? error)) (is (= 0 count))
                       (tcp/write!
                        connection fixture/PAYLOAD
                        (fn [error count]
                          (is (nil? error)) (is (= (clojure.core/count fixture/PAYLOAD) count))
                          (tcp/shutdown-output!
                           connection
                           (fn [error value]
                             (is (nil? error)) (is (nil? value))
                             (tcp/write! connection [1]
                                         (fn [error _] (is (= :output-closed (:reason (ex-data error))))))
                             (read-all! connection 11 []
                                        (fn [data]
                                          (is (= fixture/PAYLOAD data))
                                          (fixture/after!
                                           30
                                           (fn []
                                             (tcp/read! connection
                                                        (fn [error chunk]
                                                          (is (nil? error))
                                                          (is (nil? chunk))
                                                          (finish!))))))))))))))
       %))))

(deftest connect-cancellation-contract
  (when-not (is-browser?)
    (run-contract!
     (fn [done]
       (fixture/start!
        :silent
        (fn [{:keys [port stop!]}]
          (let [calls (atom 0)
                record! (fn [] (when (= 2 (swap! calls inc)) (stop! done)))
                connection (tcp/connect!
                            {:host "127.0.0.1" :port port :timeout-ms 1000}
                            (fn [error value]
                              ;; Completion and cancellation may race; either winner settles once.
                              (if error
                                (do (is (= :closed (:reason (ex-data error)))) (is (nil? value)))
                                (is (some? value)))
                              (record!)))]
            (tcp/close! connection)
            (tcp/close! connection)
            (tcp/read! connection
                       (fn [error value]
                         (is (= :closed (:reason (ex-data error))))
                         (is (nil? value))
                         (record!))))))))))

(deftest lifecycle-settles-once-contract
  (run-contract!
   (fn [done]
     (let [aborts (atom 0)
           results (atom [])
           record! (fn [error _]
                     (let [received (swap! results conj (:reason (ex-data error)))]
                       (when (= 3 (count received))
                         (is (= {nil 1 :busy 1 :closed 1} (frequencies received)))
                         (is (= 1 @aborts))
                         (done))))
           connection (internal/create-connection! nil #(swap! aborts inc) {:timeout-ms 1000} record!)
           connect-token (:token (internal/get-pending connection :connect))]
       (internal/complete! connection :connect connect-token nil connection)
       (let [write (internal/reserve! connection :write :portable.tcp/write! {:timeout-ms 1000} record!)]
         (is (some? write))
         (is (nil? (internal/reserve! connection :write :portable.tcp/write! {:timeout-ms 1000} record!)))
         (internal/terminate! connection :closed nil)
         (internal/terminate! connection :closed nil)
         (internal/complete! connection :connect connect-token nil connection)
         (internal/complete! connection :write (:token write) nil 1)
         (is (= :closed (:status @(:state connection)))))))))

(deftest pending-operation-deadline-contract
  (run-contract!
   (fn [done]
     (letfn [(check! [slots]
               (if-let [slot (first slots)]
                 (let [aborts (atom 0)
                       operation (case slot :connect :portable.tcp/connect!
                                             :read :portable.tcp/read! :write :portable.tcp/write!)
                       callback (fn [error value]
                                  (is (= {:type :portable/tcp-error :operation operation :reason :timeout}
                                         (ex-data error)))
                                  (is (nil? value))
                                  (is (= 1 @aborts))
                                  (check! (next slots)))
                       connection (internal/create-connection!
                                   nil #(swap! aborts inc)
                                   {:timeout-ms (if (= :connect slot) 20 1000)}
                                   (if (= :connect slot) callback (fn [_ _])))]
                   (when-not (= :connect slot)
                     (internal/complete! connection :connect
                                         (:token (internal/get-pending connection :connect)) nil connection)
                     (internal/reserve! connection slot operation {:timeout-ms 20} callback)))
                 (done)))]
       (check! [:connect :read :write])))))

(deftest concurrent-read-and-close-contract
  (when-not (is-browser?)
    (run-contract!
     #(with-connection!
       :silent
       (fn [connection finish!]
         (let [calls (atom 0)]
           (tcp/read! connection {:timeout-ms 1000}
                      (fn [error data]
                        (is (= 1 (swap! calls inc)))
                        (is (= {:type :portable/tcp-error :operation :portable.tcp/read! :reason :closed}
                               (ex-data error)))
                        (is (nil? data))
                        (tcp/read! connection
                                   (fn [error _]
                                     (is (= :closed (:reason (ex-data error))))
                                     (finish!)))))
           (tcp/read! connection
                      (fn [error _]
                        (is (= :busy (:reason (ex-data error))))
                        (is (nil? (tcp/close! connection)))
                        (is (nil? (tcp/close! connection)))))))
       %))))

(deftest read-timeout-closes-connection
  (when-not (is-browser?)
    (run-contract!
     #(with-connection!
       :silent
       (fn [connection finish!]
         (tcp/read! connection {:timeout-ms 30}
                    (fn [error data]
                      (is (= {:type :portable/tcp-error :operation :portable.tcp/read! :reason :timeout}
                             (ex-data error)))
                      (is (nil? data))
                      (tcp/write! connection [1]
                                  (fn [error _]
                                    (is (= :closed (:reason (ex-data error))))
                                    (finish!))))))
       %))))

(deftest completed-deadline-does-not-close-connection
  (when-not (is-browser?)
    (run-contract!
     #(with-connection!
       :echo
       (fn [connection finish!]
         (tcp/write! connection [42] {:timeout-ms 100}
                     (fn [error _]
                       (is (nil? error))
                       (fixture/after!
                        150
                        (fn []
                          (tcp/read! connection
                                     (fn [error data]
                                       (is (nil? error)) (is (= [42] data)) (finish!))))))))
       %))))

(deftest connection-refusal-contract
  (when-not (is-browser?)
    (run-contract!
     (fn [done]
       (fixture/start!
        :silent
        (fn [{:keys [port stop!]}]
          (stop! #(tcp/connect! {:host "127.0.0.1" :port port :timeout-ms 1000}
                                (fn [error value]
                                  (is (= :connection-refused (:reason (ex-data error))))
                                  (is (= :portable.tcp/connect! (:operation (ex-data error))))
                                  (is (nil? value))
                                  (done))))))))))

(deftest browser-unsupported-contract
  (when (is-browser?)
    (doseq [[f operation args]
            [[tcp/connect! :portable.tcp/connect! [{:host "127.0.0.1" :port 80} (fn [_ _])]]
             [tcp/read! :portable.tcp/read! [nil (fn [_ _])]]
             [tcp/write! :portable.tcp/write! [nil [0 255] (fn [_ _])]]
             [tcp/shutdown-output! :portable.tcp/shutdown-output! [nil (fn [_ _])]]
             [tcp/close! :portable.tcp/close! [nil]]]]
      (is (= {:type :portable/unsupported-operation :operation operation}
             (apply get-error-data f args))))))

(deftest validation-contract
  (doseq [[options reason] [[nil :expected-options-map] [{} :invalid-host]
                            [{:host "" :port 80} :invalid-host]
                            [{:host "a\u0000b" :port 80} :invalid-host]
                            [{:host "localhost" :port "80"} :invalid-port]
                            [{:host "localhost" :port 0} :invalid-port]
                            [{:host "localhost" :port 65536} :invalid-port]
                            [{:host "localhost" :port 80 :timeout-ms 0} :invalid-timeout]
                            [{:host "localhost" :port 80 :typo true} :unknown-option]]]
    (is (= {:type :portable/invalid-input :operation :portable.tcp/connect! :reason reason}
           (get-error-data tcp/connect! options (fn [_ _])))))
  (is (= :expected-callback (:reason (get-error-data tcp/connect! {:host "localhost" :port 80} nil))))
  (is (= :invalid-read-size (:reason (get-error-data tcp/read! nil {:max-bytes 0} (fn [_ _])))))
  (is (= :invalid-read-size (:reason (get-error-data tcp/read! nil {:max-bytes 1048577} (fn [_ _])))))
  (is (= :expected-byte-vector (:reason (get-error-data tcp/write! nil [-1] (fn [_ _])))))
  (is (= :write-too-large (:reason (get-error-data tcp/write! nil (vec (repeat 1048577 0)) (fn [_ _])))))
  (when-not (is-browser?)
    (doseq [[f args] [[tcp/read! [nil (fn [_ _])]] [tcp/write! [nil [] (fn [_ _])]]
                     [tcp/shutdown-output! [nil (fn [_ _])]] [tcp/close! [nil]]]]
      (is (= :invalid-connection (:reason (apply get-error-data f args)))))))
