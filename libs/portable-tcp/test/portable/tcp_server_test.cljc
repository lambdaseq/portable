(ns portable.tcp-server-test
  (:require [portable.tcp :as tcp]
            [portable.tcp-test :as client-contract]
            [portable.tcp-fixture :as fixture]
            [portable.tcp.internal :as internal]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is async]])))

(defn- is-browser? [] #?(:clj false :cljs (exists? js/window)))

(defn- run-contract! [scenario]
  #?(:clj (let [done (promise)] (scenario #(deliver done true)) (is (true? (deref done 10000 false))))
     :cljs (async done (scenario done))))

(defn- get-error-data [f & args]
  (try (apply f args) (catch #?(:clj Exception :cljs :default) error (ex-data error))))

(defn- with-listener! [scenario]
  (run-contract!
   (fn [done]
     (let [resources (atom []) finished (atom false) cancel-timer (atom nil)
           keep! (fn [handle] (swap! resources conj handle) handle)
           finish! (fn []
                     (when (compare-and-set! finished false true)
                       (when-let [cancel @cancel-timer] (cancel))
                       (doseq [handle @resources] (tcp/close! handle)) (done)))]
       (reset! cancel-timer (fixture/after! 5000 #(do (is false "Listener contract timed out") (finish!))))
       (keep! (tcp/listen! {}
                           (fn [error listener]
                             (is (nil? error))
                             (if error (finish!) (scenario listener keep! finish!)))))))))

(deftest loopback-accept-data-and-half-close
  (when-not (is-browser?)
    (with-listener!
      (fn [listener keep! finish!]
        (let [{:keys [host port]} (tcp/local-address listener)]
          (is (= "127.0.0.1" host)) (is (<= 1 port 65535))
          (doseq [[f args] [[tcp/read! [listener (fn [_ _])]]
                            [tcp/write! [listener [] (fn [_ _])]]
                            [tcp/shutdown-output! [listener (fn [_ _])]]]]
            (is (= :invalid-connection (:reason (apply get-error-data f args)))))
          (is (nil?
               (tcp/accept!
                listener {:timeout-ms 1000}
                (fn [error accepted]
                  (is (nil? error))
                  (when accepted
                    (keep! accepted)
                    (is (nil? (tcp/close! listener))) (is (nil? (tcp/close! listener)))
                    (client-contract/read-all!
                     accepted 7 []
                     (fn [data]
                       (is (= fixture/PAYLOAD data))
                       (tcp/write! accepted data
                                   (fn [error size]
                                     (is (nil? error)) (is (= (count data) size))
                                     (tcp/shutdown-output! accepted (fn [error _] (is (nil? error)))))))))))))
          (keep! (tcp/connect!
                  {:host host :port port}
                  (fn [error client]
                    (is (nil? error))
                    (when client
                      (tcp/write! client fixture/PAYLOAD
                                  (fn [error _]
                                    (is (nil? error))
                                    (tcp/shutdown-output!
                                     client
                                     (fn [error _]
                                       (is (nil? error))
                                       (client-contract/read-all! client 11 []
                                                                  (fn [data] (is (= fixture/PAYLOAD data)) (finish!))))))))))))))))

(deftest queued-clients-and-repeated-accept
  (when-not (is-browser?)
    (with-listener!
      (fn [listener keep! finish!]
        (let [connected (atom 0)]
          (doseq [_ (range 3)]
            (keep! (tcp/connect!
                    (tcp/local-address listener)
                    (fn [error _]
                      (is (nil? error))
                      (when (= 3 (swap! connected inc))
                        (letfn [(accept-next! [left]
                                  (if (zero? left) (finish!)
                                      (tcp/accept! listener
                                                   (fn [error connection]
                                                     (is (nil? error))
                                                     (when connection (keep! connection))
                                                     (accept-next! (dec left))))))]
                          (accept-next! 3))))))))))))

(deftest closing-listener-releases-undelivered-connections
  (when-not (is-browser?)
    (with-listener!
      (fn [listener keep! finish!]
        (keep! (tcp/connect!
                (tcp/local-address listener)
                (fn [error client]
                  (is (nil? error))
                  (when client
                    (tcp/close! listener)
                    (tcp/read! client {:timeout-ms 1000}
                               (fn [error value]
                                 (is (contains? #{nil :closed :connection-reset :io-error} (:reason (ex-data error))))
                                 (is (nil? value)) (finish!)))))))))))

(deftest accept-busy-timeout-and-closed-listener
  (when-not (is-browser?)
    (with-listener!
      (fn [listener _ finish!]
        (let [calls (atom 0)]
          (tcp/accept!
           listener {:timeout-ms 100}
           (fn [error value]
             (is (= 1 (swap! calls inc)))
             (is (= {:type :portable/tcp-error :operation :portable.tcp/accept! :reason :timeout} (ex-data error)))
             (is (nil? value))
             (tcp/accept! listener (fn [error value]
                                     (is (= :closed (:reason (ex-data error)))) (is (nil? value)) (finish!)))))
          (tcp/accept! listener (fn [error value]
                                  (is (= :busy (:reason (ex-data error)))) (is (nil? value)))))))))

(deftest close-settles-pending-accept
  (when-not (is-browser?)
    (with-listener!
      (fn [listener _ finish!]
        (let [calls (atom 0)]
          (tcp/accept! listener
                       (fn [error value]
                         (is (= 1 (swap! calls inc)))
                         (is (= {:type :portable/tcp-error :operation :portable.tcp/accept! :reason :closed} (ex-data error)))
                         (is (nil? value)) (finish!)))
          (tcp/close! listener) (tcp/close! listener))))))

(deftest occupied-port-and-listen-cancellation
  (when-not (is-browser?)
    (with-listener!
      (fn [listener keep! finish!]
        (keep! (tcp/listen!
                (tcp/local-address listener)
                (fn [error value]
                  (is (= :address-in-use (:reason (ex-data error))))
                  (is (= :portable.tcp/listen! (:operation (ex-data error)))) (is (nil? value))
                  (let [calls (atom 0)
                        handle (tcp/listen!
                                {}
                                (fn [error value]
                                  (is (= 1 (swap! calls inc)))
                                  (if error (do (is (= :closed (:reason (ex-data error)))) (is (nil? value)))
                                      (do (is (some? value)) (keep! value)))
                                  (finish!)))]
                    (keep! handle) (tcp/close! handle) (tcp/close! handle)))))))))

(deftest listener-lifecycle-and-late-accept-contract
  (run-contract!
   (fn [done]
     (let [closed (atom 0) aborts (atom 0) callbacks (atom 0)
           listener (internal/create-listener!
                     nil #(swap! aborts inc) {:timeout-ms 1000}
                     (fn [error _]
                       (is (= :closed (:reason (ex-data error))))
                       (is (= :portable.tcp/listen! (:operation (ex-data error))))
                       (is (= 1 (swap! callbacks inc)))
                       (is (= 1 @aborts)) (done)))
           accepted (internal/create-accepted! nil #(swap! closed inc))]
       (internal/terminate! listener :closed nil)
       (internal/deliver-accepted! listener accepted)
       (is (= 1 @closed))
       (internal/terminate! listener :closed nil)))))

(deftest listener-validation-and-browser-rejection
  (doseq [[options reason]
          [[nil :expected-options-map] [{:typo true} :unknown-option]
           [{:host "localhost"} :invalid-host] [{:host "::1"} :invalid-host] [{:host "256.0.0.1"} :invalid-host]
           [{:host "01.0.0.1"} :invalid-host] [{:host nil} :invalid-host]
           [{:port -1} :invalid-port] [{:port 65536} :invalid-port] [{:port "0"} :invalid-port]
           [{:backlog 0} :invalid-backlog] [{:backlog 1025} :invalid-backlog] [{:backlog 1.5} :invalid-backlog]
           [{:timeout-ms 0} :invalid-timeout]]]
    (is (= {:type :portable/invalid-input :operation :portable.tcp/listen! :reason reason}
           (get-error-data tcp/listen! options (fn [_ _])))))
  (is (= :expected-callback (:reason (get-error-data tcp/listen! {} nil))))
  (is (= :invalid-timeout (:reason (get-error-data tcp/accept! nil {:timeout-ms 0} (fn [_ _])))))
  (is (= :unknown-option (:reason (get-error-data tcp/accept! nil {:port 1} (fn [_ _])))))
  (if (is-browser?)
    (doseq [[operation f args] [[:portable.tcp/listen! tcp/listen! [{} (fn [_ _])]]
                              [:portable.tcp/accept! tcp/accept! [nil (fn [_ _])]]
                              [:portable.tcp/local-address tcp/local-address [nil]]]]
      (is (= {:type :portable/unsupported-operation :operation operation} (apply get-error-data f args))))
    (do
      (is (= :invalid-listener (:reason (get-error-data tcp/accept! nil (fn [_ _])))))
      (is (= :invalid-listener (:reason (get-error-data tcp/local-address nil))))
      (let [connection (internal/create-accepted! nil (fn []))]
        (is (= :invalid-listener (:reason (get-error-data tcp/accept! connection (fn [_ _])))))
        (internal/terminate! connection :closed nil)))))
