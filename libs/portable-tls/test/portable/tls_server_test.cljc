(ns portable.tls-server-test
  (:require [portable.tls :as tls]
            [portable.tcp :as tcp]
            #?(:clj [portable.tcp.internal :as transport])
            [portable.tls-fixture :as fixture]
            [portable.tls-test :as client-contract]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is async]]))
  #?(:clj (:import [java.util.concurrent TimeUnit])))

(def CERT "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----")
(def KEY "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----")
(def SHAPE {:cert-chain [CERT] :private-key KEY})

(defn- is-browser? [] #?(:clj false :cljs (exists? js/window)))

(defn- get-error-data [f & args]
  (try (apply f args) (catch #?(:clj Exception :cljs :default) error (ex-data error))))

(defn- read-fixture [name]
  #?(:clj (slurp (str "test/fixtures/" name))
     :cljs (.readFileSync (js/require "node:fs") (str "test/fixtures/" name) "utf8")))

(defn- identity-options []
  {:cert-chain [(read-fixture "server.pem") (fixture/read-ca)] :private-key (read-fixture "server-key.pem")})

(defn- run-contract! [scenario]
  #?(:clj (let [done (promise)] (scenario #(deliver done true)) (is (true? (deref done 20000 false))))
     :cljs (async done (scenario done))))

(defn- with-listener! [scenario]
  (run-contract!
   (fn [done]
     (let [resources (atom []) finished (atom false) cancel (atom nil)
           keep! (fn [close! resource] (swap! resources conj [close! resource]) resource)
           finish! (fn []
                     (when (compare-and-set! finished false true)
                       (when-let [cancel! @cancel] (cancel!))
                       (doseq [[close! resource] @resources] (close! resource)) (done)))]
       (reset! cancel (fixture/after! 15000 #(do (is false "Server TLS contract timed out") (finish!))))
       (keep! tcp/close!
              (tcp/listen! {} (fn [error listener]
                                (is (nil? error))
                                (if error (finish!) (scenario listener keep! finish!)))))))))

(defn- accept-tls! [listener options keep! callback]
  (tcp/accept! listener
               (fn [error connection]
                 (is (nil? error))
                 (when connection
                   (keep! tcp/close! connection)
                   (let [upgrade! #(keep! tls/close! (tls/accept! connection (dissoc options :upgrade-delay-ms) callback))]
                     (if-let [delay-ms (:upgrade-delay-ms options)]
                       (fixture/after! delay-ms upgrade!)
                       (upgrade!)))))))

(defn- connect-client! [listener keep! callback]
  (keep! tls/close!
         (tls/connect! (assoc (tcp/local-address listener) :ca-certs [(fixture/read-ca)] :alpn ["http/1.1"])
                       callback)))

(deftest server-tls-byte-stream-ownership-and-session
  (when-not (is-browser?)
    (with-listener!
      (fn [listener keep! finish!]
        (accept-tls!
         listener (assoc (identity-options) :alpn ["http/1.1"]) keep!
         (fn [error server]
           (is (nil? error))
           (if error (finish!)
               (do
                 (is (#{"TLSv1.2" "TLSv1.3"} (:version (tls/get-session-info server))))
                 (is (= "http/1.1" (:alpn (tls/get-session-info server))))
                 (is (= :invalid-connection (:reason (get-error-data tcp/read! server (fn [_ _])))))
                 (tcp/close! listener)
                 (client-contract/read-all!
                  server []
                  (fn [data]
                    (is (= fixture/PAYLOAD data))
                    (tls/write! server data
                                (fn [error count]
                                  (is (nil? error)) (is (= (clojure.core/count data) count))
                                  (tls/shutdown-output! server (fn [error _] (is (contains? #{nil :closed} (:reason (ex-data error))))))))))))))
        (connect-client!
         listener keep!
         (fn [error client]
           (is (nil? error))
           (if error (finish!)
               (tls/write! client fixture/PAYLOAD
                           (fn [error _]
                             (is (nil? error))
                             (tls/shutdown-output!
                              client
                              (fn [error _]
                                (is (nil? error))
                                (client-contract/read-all! client []
                                                           (fn [data]
                                                             (is (= fixture/PAYLOAD data))
                                                             (finish!))))))))))))))

(deftest server-handshake-timeout-and-cancellation
  (when-not (is-browser?)
    (doseq [cancel? [false true]]
      (with-listener!
        (fn [listener keep! finish!]
          (tcp/accept!
           listener
           (fn [error raw]
             (is (nil? error))
             (when raw
               (keep! tcp/close! raw)
               (let [calls (atom 0)
                     connection (keep! tls/close!
                                       (tls/accept!
                                        raw (assoc (identity-options) :timeout-ms (if cancel? 5000 100))
                                        (fn [error value]
                                          (is (= 1 (swap! calls inc)))
                                          (is (= {:type :portable/tls-error :operation :portable.tls/accept!
                                                  :reason (if cancel? :closed :timeout)} (ex-data error)))
                                          (is (nil? value))
                                          (tcp/read! raw (fn [error _]
                                                           (is (= :closed (:reason (ex-data error)))) (finish!))))))]
                 (is (= :not-connected (:reason (get-error-data tls/get-session-info connection))))
                 (when cancel? (tls/close! connection) (tls/close! connection))))))
          (keep! tcp/close! (tcp/connect! (tcp/local-address listener) (fn [error _] (is (contains? #{nil :closed} (:reason (ex-data error))))))))))))

(deftest plaintext-peers-fail-the-server-handshake
  (when-not (is-browser?)
    (with-listener!
      (fn [listener keep! finish!]
        (accept-tls!
         listener (identity-options) keep!
         (fn [error value]
           (is (= {:type :portable/tls-error :operation :portable.tls/accept! :reason :handshake-failed}
                  (ex-data error)))
           (is (nil? value)) (finish!)))
        (keep! tcp/close!
               (tcp/connect! (tcp/local-address listener)
                             (fn [error raw]
                               (is (nil? error))
                               (when raw
                                 (tcp/write! raw [71 69 84 32 47 32 72 84 84 80 47 49 46 49 13 10 13 10]
                                             (fn [_ _]))))))))))

(deftest absent-server-alpn-and-session-after-close
  (when-not (is-browser?)
    (with-listener!
      (fn [listener keep! finish!]
        (accept-tls!
         listener (identity-options) keep!
         (fn [error server]
           (is (nil? error))
           (if error (finish!)
               (let [session (tls/get-session-info server)]
                 (is (nil? (:alpn session)))
                 (tls/close! server)
                 (is (= session (tls/get-session-info server)))
                 (finish!)))))
        (connect-client! listener keep! (fn [_ _]))))))

(deftest malformed-native-identity-closes-owned-tcp
  (when-not (is-browser?)
    (with-listener!
      (fn [listener keep! finish!]
        (accept-tls!
         listener SHAPE keep!
         (fn [error value]
           (is (= {:type :portable/tls-error :operation :portable.tls/accept! :reason :invalid-identity} (ex-data error)))
           (is (nil? value)) (finish!)))
        (keep! tcp/close! (tcp/connect! (tcp/local-address listener) (fn [error _] (is (contains? #{nil :closed} (:reason (ex-data error)))))))))))

(deftest busy-tcp-rejects-transfer-without-taking-ownership
  (when-not (is-browser?)
    (with-listener!
      (fn [listener keep! finish!]
        (tcp/accept!
         listener
         (fn [error raw]
           (is (nil? error))
           (when raw
             (keep! tcp/close! raw)
             (tcp/read! raw (fn [error _] (is (= :closed (:reason (ex-data error)))) (finish!)))
             (is (= {:type :portable/tls-error :operation :portable.tls/accept! :reason :busy}
                    (get-error-data tls/accept! raw (identity-options) (fn [_ _]))))
             (tcp/close! raw))))
        (keep! tcp/close! (tcp/connect! (tcp/local-address listener) (fn [error _] (is (contains? #{nil :closed} (:reason (ex-data error)))))))))))

(defn- start-native-client! [port version ca-file callback]
  #?(:clj
     (let [process (-> (ProcessBuilder. ^java.util.List ["node" "test/fixtures/client.cjs" (str port) version ca-file])
                       (.inheritIO) (.start))]
       (transport/dispatch! #(do (when-not (.waitFor process 10 TimeUnit/SECONDS) (.destroyForcibly process))
                                  (callback (.waitFor process))))
       (fn [_] (.destroy process)))
     :cljs
     (let [process (.spawn (js/require "node:child_process") "node"
                          #js ["test/fixtures/client.cjs" (str port) version ca-file] #js {:stdio "inherit"})]
       (.once process "exit" (fn [code _] (callback code)))
       (fn [_] (.kill process)))))

(deftest native-client-tls12-tls13-and-server-alpn-preference
  (when-not (is-browser?)
    (doseq [[version ec?] [["TLSv1.2" false] ["TLSv1.3" false] ["TLSv1.3" true]]]
      (with-listener!
        (fn [listener keep! finish!]
          (accept-tls!
           listener (assoc (if ec?
                            {:cert-chain [(read-fixture "ec-server.pem")]
                             :private-key (read-fixture "ec-server-key.pem")}
                            (identity-options))
                          :alpn ["h2" "http/1.1"] :upgrade-delay-ms 100) keep!
           (fn [error server]
             (is (nil? error))
             (when server
               (is (= {:version version :alpn "h2"} (tls/get-session-info server)))
               (letfn [(read-next! [received]
                         (tls/read!
                          server
                          (fn [error data]
                            (is (nil? error))
                            (let [received (into received data)]
                              (if (< (count received) (count fixture/PAYLOAD))
                                (if data (read-next! received) (do (is false "Unexpected EOF") (finish!)))
                                (do (is (= fixture/PAYLOAD received))
                                    (tls/write! server received
                                                (fn [error _]
                                                  (is (nil? error))
                                                  (tls/shutdown-output! server (fn [error _] (is (contains? #{nil :closed} (:reason (ex-data error))))))))))))))]
                 (read-next! [])))))
          (keep! (start-native-client! (:port (tcp/local-address listener)) version (if ec? "ec-server.pem" "ca.pem")
                                       (fn [exit] (is (= 0 exit)) (finish!))) nil))))))

(deftest server-validation-and-browser-rejection
  (doseq [[options reason]
          [[nil :expected-options-map] [{} :invalid-cert-chain]
           [(assoc SHAPE :cert-chain []) :invalid-cert-chain]
           [(assoc SHAPE :cert-chain ["bad"]) :invalid-cert-chain]
           [(dissoc SHAPE :private-key) :invalid-private-key]
           [(assoc SHAPE :private-key "-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----") :invalid-private-key]
           [(assoc SHAPE :private-key (apply str (repeat 65537 "a"))) :invalid-private-key]
           [(assoc SHAPE :timeout-ms 0) :invalid-timeout]
           [(assoc SHAPE :alpn [""]) :invalid-alpn]
           [(assoc SHAPE :alpn ["λ"]) :invalid-alpn]
           [(assoc SHAPE :request-cert true) :unknown-option]]]
    (is (= {:type :portable/invalid-input :operation :portable.tls/accept! :reason reason}
           (get-error-data tls/accept! nil options (fn [_ _])))))
  (is (= :expected-callback (:reason (get-error-data tls/accept! nil SHAPE nil))))
  (is (= (if (is-browser?) {:type :portable/unsupported-operation :operation :portable.tls/accept!}
             {:type :portable/invalid-input :operation :portable.tls/accept! :reason :invalid-connection})
         (get-error-data tls/accept! nil SHAPE (fn [_ _])))))
