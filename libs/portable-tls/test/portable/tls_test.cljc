(ns portable.tls-test
  (:require [portable.tls :as tls]
            [portable.bytes :as bytes]
            [portable.tcp :as tcp]
            [portable.tls-fixture :as fixture]
            #?(:clj [clojure.test :as test :refer [deftest is]]
               :cljs [cljs.test :as test :refer-macros [deftest is async]])))

(defn- is-browser? [] #?(:clj false :cljs (exists? js/window)))

(defn- get-error-data [f & args]
  (try (apply f args)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error (ex-data error))))

(defn- run-contract! [scenario]
  #?(:clj (let [finished (promise)]
            (scenario #(deliver finished :done))
            (is (= :done (deref finished 20000 :timeout))))
     :cljs (async done (scenario done))))

(defn- with-connection! [mode extra-options scenario done]
  (fixture/start!
   mode
   (fn [{:keys [port stop!]}]
     (let [handle (atom nil)
           finished (atom false)
           cancel-watchdog (atom nil)
           finish! (fn []
                     (when (compare-and-set! finished false true)
                       (when-let [cancel @cancel-watchdog] (cancel))
                       (when-let [connection @handle] (tls/close! connection))
                       (stop! done)))]
       (reset! cancel-watchdog
               (fixture/after! 15000 #(do (is false "TLS contract timed out.") (finish!))))
       (reset! handle
               (tls/connect! (merge {:host "127.0.0.1" :port port :timeout-ms 5000
                                     :ca-certs [(fixture/read-ca)] :alpn ["http/1.1"]}
                                    extra-options)
                             (fn [error connection] (scenario error connection finish!))))))))

(defn read-all! [connection received done]
  (tls/read! connection {:max-bytes 7 :timeout-ms 2000}
             (fn [error data]
               (is (nil? error))
               (when data (is (vector? data)) (is (<= 1 (count data) 7)))
               (if (or error (nil? data))
                 (done received)
                 (read-all! connection (into received data) done)))))

(deftest trusted-byte-stream-and-session
  (when-not (is-browser?)
    (run-contract!
     #(with-connection!
       :payload {}
       (fn [error connection finish!]
         (is (nil? error))
         (if error
           (finish!)
           (do
             (is (#{"TLSv1.2" "TLSv1.3"} (:version (tls/get-session-info connection))))
             (is (= "http/1.1" (:alpn (tls/get-session-info connection))))
             (is (= :invalid-connection (:reason (get-error-data tcp/read! connection (fn [_ _])))))
             (read-all! connection []
                        (fn [data]
                          (is (= fixture/PAYLOAD data))
                          (tls/read! connection
                                     (fn [error data]
                                       (is (nil? error)) (is (nil? data)) (finish!))))))))
       %))))

(deftest encrypted-write-and-output-shutdown
  (when-not (is-browser?)
    (run-contract!
     #(with-connection!
       :echo {}
       (fn [error connection finish!]
         (is (nil? error))
         (if error
           (finish!)
           (tls/write! connection fixture/PAYLOAD
                       (fn [error accepted]
                         (is (nil? error)) (is (= (count fixture/PAYLOAD) accepted))
                         (tls/shutdown-output!
                          connection
                          (fn [error _]
                            (is (nil? error))
                            (read-all! connection []
                                       (fn [data] (is (= fixture/PAYLOAD data)) (finish!)))))))))
       %))))

(deftest explicit-peer-name-and-sni
  (when-not (is-browser?)
    (run-contract!
     #(with-connection!
       :sni {:server-name "localhost.test"}
       (fn [error connection finish!]
         (is (nil? error))
         (if error
           (finish!)
           (read-all! connection []
                      (fn [data]
                        (is (= "localhost.test" (bytes/decode-utf8 data)))
                        (finish!)))))
       %))))

(deftest encrypted-read-deadline-and-session-after-close
  (when-not (is-browser?)
    (run-contract!
     #(with-connection!
       :echo {}
       (fn [error connection finish!]
         (is (nil? error))
         (if error
           (finish!)
           (let [session (tls/get-session-info connection)]
             (tls/read! connection {:timeout-ms 30}
                        (fn [error data]
                          (is (= {:type :portable/tls-error :operation :portable.tls/read! :reason :timeout}
                                 (ex-data error)))
                          (is (nil? data))
                          (is (= session (tls/get-session-info connection)))
                          (tls/write! connection [1]
                                      (fn [error _]
                                        (is (= :closed (:reason (ex-data error))))
                                        (finish!))))))))
       %))))

(deftest tls12-and-tls13-and-no-alpn
  (when-not (is-browser?)
    (run-contract!
     (fn [done]
       (letfn [(check! [modes]
                 (if-let [[mode version] (first modes)]
                   (with-connection!
                     mode {:alpn []}
                     (fn [error connection finish!]
                       (is (nil? error))
                       (when connection
                         (is (= {:version version :alpn nil} (tls/get-session-info connection))))
                       (finish!))
                     #(check! (next modes)))
                   (done)))]
         (check! [[:tls12 "TLSv1.2"] [:tls13 "TLSv1.3"]]))))))

(deftest certificate-and-handshake-rejections
  (when-not (is-browser?)
    (run-contract!
     (fn [done]
       (letfn [(check! [cases]
                 (if-let [[mode options expected] (first cases)]
                   (with-connection!
                     mode options
                     (fn [error value finish!]
                       (is (= {:type :portable/tls-error :operation :portable.tls/connect! :reason expected}
                              (ex-data error)))
                       (is (nil? value))
                       (finish!))
                     #(check! (next cases)))
                   (done)))]
         ;; Omit explicit trust to check native default roots reject the private fixture CA.
         (fixture/start!
          :echo
          (fn [{:keys [port stop!]}]
            (tls/connect! {:host "127.0.0.1" :port port :timeout-ms 5000}
                          (fn [error value]
                            (is (= :certificate-rejected (:reason (ex-data error))))
                            (is (nil? value))
                            (stop! #(check! [[:echo {:server-name "wrong.example"} :certificate-rejected]
                                             [:ip-mismatch {} :certificate-rejected]
                                             [:expired {} :certificate-rejected]
                                             [:echo {:ca-certs ["-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----"]} :invalid-ca]
                                             [:plain {} :handshake-failed]])))))))))))

(deftest handshake-timeout-contract
  (when-not (is-browser?)
    (run-contract!
     #(with-connection!
       :silent {:timeout-ms 100}
       (fn [error value finish!]
         (is (= {:type :portable/tls-error :operation :portable.tls/connect! :reason :timeout}
                (ex-data error)))
         (is (nil? value))
         (finish!))
       %))))

(deftest connect-cancellation-contract
  (when-not (is-browser?)
    (run-contract!
     (fn [done]
       (fixture/start!
        :silent
        (fn [{:keys [port stop!]}]
          (let [handle (tls/connect!
                        {:host "127.0.0.1" :port port :timeout-ms 2000 :ca-certs [(fixture/read-ca)]}
                        (fn [error value]
                          (is (= :closed (:reason (ex-data error))))
                          (is (nil? value))
                          (stop! done)))]
            (is (= :not-connected (:reason (get-error-data tls/get-session-info handle))))
            (tls/close! handle)
            (is (nil? (tls/close! handle))))))))))

(deftest browser-unsupported-contract
  (when (is-browser?)
    (doseq [[f operation args]
            [[tls/connect! :portable.tls/connect! [{:host "localhost" :port 443} (fn [_ _])]]
             [tls/read! :portable.tls/read! [nil (fn [_ _])]]
             [tls/write! :portable.tls/write! [nil [0 255] (fn [_ _])]]
             [tls/shutdown-output! :portable.tls/shutdown-output! [nil (fn [_ _])]]
             [tls/close! :portable.tls/close! [nil]]
             [tls/get-session-info :portable.tls/get-session-info [nil]]]]
      (is (= {:type :portable/unsupported-operation :operation operation}
             (apply get-error-data f args))))))

(deftest input-validation-contract
  (doseq [[options reason] [[nil :expected-options-map]
                            [{} :invalid-host]
                            [{:host "localhost" :port 0} :invalid-port]
                            [{:host "localhost" :port 443 :timeout-ms 0} :invalid-timeout]
                            [{:host "localhost" :port 443 :server-name ""} :invalid-server-name]
                            [{:host "localhost" :port 443 :server-name "localhost"} :invalid-server-name]
                            [{:host "localhost" :port 443 :ca-certs []} :invalid-ca-certs]
                            [{:host "localhost" :port 443 :ca-certs "pem"} :invalid-ca-certs]
                            [{:host "localhost" :port 443 :alpn [""]} :invalid-alpn]
                            [{:host "localhost" :port 443 :alpn ["λ"]} :invalid-alpn]
                            [{:host "localhost" :port 443 :reject-unauthorized false} :unknown-option]]]
    (is (= {:type :portable/invalid-input :operation :portable.tls/connect! :reason reason}
           (get-error-data tls/connect! options (fn [_ _])))))
  (is (= :expected-callback (:reason (get-error-data tls/connect! {:host "localhost" :port 443} nil))))
  (is (= :invalid-read-size (:reason (get-error-data tls/read! nil {:max-bytes 0} (fn [_ _])))))
  (is (= :expected-byte-vector (:reason (get-error-data tls/write! nil [-1] (fn [_ _])))))
  (when-not (is-browser?)
    (is (= :invalid-connection (:reason (get-error-data tls/get-session-info nil))))))
