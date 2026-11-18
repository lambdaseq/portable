(ns portable.udp-test
  (:require [portable.udp :as udp]
            [portable.udp-fixture :as fixture]
            [portable.udp.internal :as internal]
            #?(:clj [clojure.test :as test :refer [deftest is]]
               :cljs [cljs.test :as test :refer-macros [deftest is async]])))

(defn- is-browser? [] #?(:clj false :cljs (exists? js/window)))

(defn- get-error-data [f & args]
  (try (apply f args)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error (ex-data error))))

(defn- run-contract! [scenario]
  #?(:clj (let [finished (promise)]
            (scenario #(deliver finished :done))
            (is (= :done (deref finished 12000 :timeout))))
     :cljs (async done (scenario done))))

(defn- with-socket! [options scenario done]
  (let [socket (atom nil)
        completed (atom false)
        cancel-watchdog (atom nil)
        finish! (fn []
                  (when (compare-and-set! completed false true)
                    (when-let [cancel @cancel-watchdog] (cancel))
                    (when-let [handle @socket] (udp/close! handle))
                    (done)))]
    (reset! cancel-watchdog (fixture/after! 5000 #(do (is false "UDP contract timed out.") (finish!))))
    (let [handle (udp/bind! options
                            (fn [error bound]
                              (is (nil? error))
                              (if error (finish!) (scenario bound finish!))))]
      (reset! socket handle)
      (when @completed (udp/close! handle)))))

(defn- with-echo! [options scenario done]
  (fixture/start! (fn [{:keys [stop!] :as peer}]
                    (with-socket! options
                                  (fn [bound finish!] (scenario bound peer finish!))
                                  #(do (stop!) (done))))))

(defn- round-trip! [socket peer data receive-options check!]
  (let [events (atom 0)
        received (atom nil)
        complete! (fn [] (when (= 2 (swap! events inc)) (check! @received)))]
    (is (nil? (udp/receive! socket receive-options
                            (fn [error packet] (reset! received [error packet]) (complete!)))))
    (is (nil? (udp/send! socket data (select-keys peer [:host :port])
                         (fn [error size] (is (nil? error)) (is (= (count data) size)) (complete!)))))))

(deftest bytes-boundaries-and-sender-metadata
  (when-not (is-browser?)
    (run-contract!
     #(with-echo!
       {}
       (fn [{:keys [socket host port]} peer finish!]
         (is (= "127.0.0.1" host))
         (is (<= 1 port 65535))
         (letfn [(check! [payloads]
                   (if-let [entry (first payloads)]
                     (round-trip!
                      socket peer entry {:max-bytes (count entry)}
                      (fn [[error packet]]
                        (is (nil? error))
                        (is (= {:data entry :host (:host peer) :port (:port peer)} packet))
                        (check! (next payloads))))
                     (finish!)))]
           (check! [[] [0] [0 255 128 1] (vec (range 256)) (vec (repeat 8192 255))])))
       %))))

(deftest wildcard-and-explicit-port-binding
  (when-not (is-browser?)
    (run-contract!
     #(with-echo!
       {:host "0.0.0.0" :port 0}
       (fn [{:keys [socket host]} peer finish!]
         (is (= "0.0.0.0" host))
         (round-trip! socket peer [12] {} (fn [[error packet]]
                                            (is (nil? error)) (is (= [12] (:data packet))) (finish!))))
       %))))

(deftest oversized-receive-preserves-socket-and-next-packet
  (when-not (is-browser?)
    (run-contract!
     #(with-echo!
       {}
       (fn [{:keys [socket]} peer finish!]
         (round-trip!
          socket peer [1 2 3 4] {:max-bytes 3}
          (fn [[error packet]]
            (is (= {:type :portable/udp-error :operation :portable.udp/receive! :reason :datagram-too-large} (ex-data error)))
            (is (nil? packet))
            (round-trip! socket peer [255] {} (fn [[error packet]]
                                               (is (nil? error)) (is (= [255] (:data packet))) (finish!))))))
       %))))

(deftest concurrent-receives-report-busy-without-cancelling
  (when-not (is-browser?)
    (run-contract!
     #(with-echo!
       {}
       (fn [{:keys [socket]} peer finish!]
         (let [events (atom 0)
               complete! (fn [] (when (= 3 (swap! events inc)) (finish!)))]
           (udp/receive! socket (fn [error packet] (is (nil? error)) (is (= [42] (:data packet))) (complete!)))
           (udp/receive!
            socket
            (fn [error value]
              (is (= :busy (:reason (ex-data error)))) (is (nil? value))
              (udp/send! socket [42] (select-keys peer [:host :port])
                         (fn [error size] (is (nil? error)) (is (= 1 size)) (complete!)))
              (complete!)))))
       %))))

(deftest deadlines-close-socket-and-settle-once
  (when-not (is-browser?)
    (run-contract!
     #(with-socket!
       {}
       (fn [{:keys [socket]} finish!]
         (let [calls (atom 0)]
           (udp/receive!
            socket {:timeout-ms 40}
            (fn [error packet]
              (is (= 1 (swap! calls inc)))
              (is (= {:type :portable/udp-error :operation :portable.udp/receive! :reason :timeout} (ex-data error)))
              (is (nil? packet))
              (udp/send! socket [] {:host "127.0.0.1" :port 9}
                         (fn [error value] (is (= :closed (:reason (ex-data error)))) (is (nil? value)) (finish!)))))))
       %))))

(deftest close-cancels-pending-and-future-operations
  (when-not (is-browser?)
    (run-contract!
     #(with-socket!
       {}
       (fn [{:keys [socket]} finish!]
         (let [calls (atom 0)
               check! (fn [error value]
                        (is (= :closed (:reason (ex-data error)))) (is (nil? value))
                        (when (= 3 (swap! calls inc)) (finish!)))]
           (udp/receive! socket check!)
           (is (nil? (udp/close! socket)))
           (is (nil? (udp/close! socket)))
           (udp/receive! socket check!)
           (udp/send! socket [] {:host "127.0.0.1" :port 9} check!)))
       %))))

(deftest occupied-port-fails-asynchronously
  (when-not (is-browser?)
    (run-contract!
     (fn [done]
       (fixture/start!
        (fn [{:keys [host port stop!]}]
          (udp/bind! {:host host :port port}
                     (fn [error bound]
                       (is (= {:type :portable/udp-error :operation :portable.udp/bind! :reason :bind-failed} (ex-data error)))
                       (is (nil? bound)) (stop!) (done)))))))))

(deftest bind-cancellation-settles-once
  (when-not (is-browser?)
    (run-contract!
     (fn [done]
       (let [calls (atom 0)
             socket (udp/bind! {}
                               (fn [error bound]
                                 (is (= 1 (swap! calls inc)))
                                 (if error
                                   (do (is (= :closed (:reason (ex-data error)))) (is (nil? bound)))
                                   (udp/close! (:socket bound)))
                                 (done)))]
         (udp/close! socket)
         (udp/close! socket))))))

(deftest malformed-inputs-are-synchronous-on-every-runtime
  (let [callback (fn [_ _])
        destination {:host "127.0.0.1" :port 9}]
    (doseq [[options reason] [[nil :expected-options-map] [{:wat true} :unknown-option]
                             [{:host "localhost"} :invalid-address] [{:host "::1"} :invalid-address]
                             [{:host "127.00.0.1"} :invalid-address] [{:host "256.0.0.1"} :invalid-address]
                             [{:host "224.0.0.1"} :invalid-address] [{:host "255.255.255.255"} :invalid-address]
                             [{:port -1} :invalid-port] [{:port 65536} :invalid-port]
                             [{:timeout-ms 0} :invalid-timeout] [{:timeout-ms 2147483648} :invalid-timeout]]]
      (is (= {:type :portable/invalid-input :operation :portable.udp/bind! :reason reason}
             (get-error-data udp/bind! options callback))))
    (is (= :expected-callback (:reason (get-error-data udp/bind! {} nil))))
    (doseq [data [nil [256] [-1] [1.5] '(0 1) "abc"]]
      (is (= :expected-byte-vector (:reason (get-error-data udp/send! nil data destination callback)))))
    (is (= :datagram-too-large (:reason (get-error-data udp/send! nil (vec (repeat 65508 0)) destination callback))))
    (is (= :invalid-address (:reason (get-error-data udp/send! nil [] {:host "0.0.0.0" :port 9} callback))))
    (is (= :invalid-port (:reason (get-error-data udp/send! nil [] (assoc destination :port 0) callback))))
    (doseq [limit [-1 65508 1.5 nil]]
      (is (= :invalid-receive-size (:reason (get-error-data udp/receive! nil {:max-bytes limit} callback)))))
    (when-not (is-browser?)
      (is (= :invalid-socket (:reason (get-error-data udp/send! nil [] destination callback))))
      (is (= :invalid-socket (:reason (get-error-data udp/receive! {} callback))))
      (is (= :invalid-socket (:reason (get-error-data udp/close! nil)))))))

(deftest browsers-reject-all-socket-operations-explicitly
  (when (is-browser?)
    (let [calls (atom 0)
          callback (fn [_ _] (swap! calls inc))]
      (doseq [[operation f args] [[:portable.udp/bind! udp/bind! [{} callback]]
                                 [:portable.udp/send! udp/send! [nil [] {:host "127.0.0.1" :port 9} callback]]
                                 [:portable.udp/receive! udp/receive! [nil callback]]
                                 [:portable.udp/close! udp/close! [nil]]]]
        (is (= {:type :portable/unsupported-operation :operation operation} (apply get-error-data f args))))
      (is (zero? @calls)))))

(deftest queued-packets-preserve-boundaries
  (when-not (is-browser?)
    (run-contract!
     #(with-echo!
       {}
       (fn [{:keys [socket]} peer finish!]
         (let [payloads [[] [1 2 3] [255 0]]]
           (letfn [(receive-next! [packets]
                     (if (= 3 (count packets))
                       (do (is (= (set payloads) (set packets))) (finish!))
                       (udp/receive! socket (fn [error packet]
                                              (is (nil? error))
                                              (receive-next! (conj packets (:data packet)))))))
                   (send-next! [remaining]
                     (if-let [payload (first remaining)]
                       (udp/send! socket payload (select-keys peer [:host :port])
                                  (fn [error size]
                                    (is (nil? error)) (is (= (count payload) size))
                                    (send-next! (next remaining))))
                       (fixture/after! 30 (fn [] (receive-next! [])))))]
             (send-next! payloads))))
       %))))

(deftest late-bind-completion-cannot-reopen-cancelled-socket
  (run-contract!
   (fn [done]
     (let [calls (atom 0)
           closed (atom 0)
           check! (fn [reason]
                    (fn [error value]
                      (is (= reason (:reason (ex-data error)))) (is (nil? value))
                      (when (= 2 (swap! calls inc)) (done))))
           socket (internal/create-socket! (fn [_] (swap! closed inc)) {:timeout-ms 5000} (check! :closed))
           pending (internal/get-pending socket :bind)]
       (internal/reserve! socket :send {:timeout-ms 5000} (check! :not-bound))
       (internal/terminate! socket :closed nil)
       (internal/set-native! socket :late-resource)
       (internal/complete! socket :bind (:token pending) nil {})
       (internal/terminate! socket :closed nil)
       (is (= 1 @closed))
       (is (= :closed (:status @(:state socket))))))))

(deftest timeout-cancels-both-pending-directions-once
  (run-contract!
   (fn [done]
     (let [calls (atom 0)
           socket (internal/create-socket! (fn [_]) {:timeout-ms 5000} (fn [_ _]))
           bound (internal/get-pending socket :bind)
           check! (fn [reason]
                    (fn [error value]
                      (is (= reason (:reason (ex-data error)))) (is (nil? value))
                      (when (= 3 (swap! calls inc)) (done))))]
       (internal/complete! socket :bind (:token bound) nil {})
       (internal/reserve! socket :send {:timeout-ms 5000} (check! :timeout))
       (internal/reserve! socket :send {:timeout-ms 5000} (check! :busy))
       (internal/reserve! socket :receive {:timeout-ms 40 :max-bytes 1} (check! :timeout))))))

(deftest bounded-queue-keeps-whole-packets
  (run-contract!
   (fn [done]
     (let [calls (atom 0)]
       (doseq [[data expected] [[[] 64] [(vec (repeat 65507 0)) 16]]]
         (let [socket (internal/create-socket! (fn [_]) {:timeout-ms 5000} (fn [_ _]))
               bound (internal/get-pending socket :bind)]
           (internal/complete! socket :bind (:token bound) nil {})
           (dotimes [index 65] (internal/enqueue! socket {:data data :host "127.0.0.1" :port (inc index)}))
           (is (= expected (count (:queue @(:state socket)))))
           (is (= (* expected (count data)) (:queued-bytes @(:state socket))))
           (let [pending (internal/reserve! socket :receive {:timeout-ms 5000 :max-bytes 65507}
                                             (fn [error value]
                                               (is (nil? error)) (is (= 1 (:port value)))
                                               (is (= data (:data value)))
                                               (when (= 2 (swap! calls inc)) (done))))
                 packet (internal/take-datagram! socket)]
             (internal/complete! socket :receive (:token pending) nil packet))
           (is (= (dec expected) (count (:queue @(:state socket)))))
           (is (= (* (dec expected) (count data)) (:queued-bytes @(:state socket))))
           (internal/terminate! socket :closed nil)))))))
