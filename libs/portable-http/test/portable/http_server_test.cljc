(ns portable.http-server-test
  (:require [portable.http :as http]
            [portable.bytes :as bytes]
            [portable.tcp :as tcp]
            [portable.tcp.internal :as transport]
            [portable.tls :as tls]
            [portable.process-fixture :as fixture]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is async]])))

(def PAYLOAD [0 255 206 187 0])
(defn- is-browser? [] #?(:clj false :cljs (exists? js/window)))

(defn- get-error-data [f & args]
  (try (apply f args) (catch #?(:clj Exception :cljs :default) error (ex-data error))))

(defn- run-contract! [scenario]
  #?(:clj (let [done (promise)] (scenario #(deliver done true)) (is (true? (deref done 20000 false))))
     :cljs (async done (scenario done))))

(defn- with-listener! [scenario]
  (run-contract!
   (fn [done]
     (let [resources (atom []) finished (atom false)
           keep! (fn [close! resource] (swap! resources conj [close! resource]) resource)
           timer (atom nil)
           finish! (fn []
                     (when (compare-and-set! finished false true)
                       (transport/cancel-scheduled! @timer)
                       (doseq [[close! resource] @resources] (close! resource)) (done)))]
       (reset! timer (transport/schedule! 15000 #(do (is false "HTTP server contract timed out") (finish!))))
       (keep! tcp/close!
              (tcp/listen! {} (fn [error listener]
                                (is (nil? error))
                                (if error (finish!) (scenario listener keep! finish!)))))))))

(defn- read-all! [connection received callback]
  (tcp/read! connection
             (fn [error data]
               (is (nil? error))
               (if (and (nil? error) data)
                 (read-all! connection (into received data) callback)
                 (callback received)))))

(defn- read-fixture [name]
  #?(:clj (slurp (str "../portable-tls/test/fixtures/" name))
     :cljs (.readFileSync (js/require "node:fs") (str "../portable-tls/test/fixtures/" name) "utf8")))

(deftest native-clients-fixed-chunked-head-no-content-and-https
  (when-not (is-browser?)
    (doseq [mode ["fixed" "chunked" "head" "empty" "https"]]
      (with-listener!
        (fn [listener keep! finish!]
          (let [secure? (= mode "https") completions (atom 0)
                completed! #(when (= 2 (swap! completions inc)) (finish!))
                serve! (fn [connection]
                         (keep! (fn [cancel!] (cancel!))
                                (http/serve!
                                 connection {:tls? secure?}
                                 (fn [request respond!]
                                   (is (= "/echo?q=1" (:target request)))
                                   (is (= (if (#{"head" "empty"} mode) [] PAYLOAD) (:body request)))
                                   ;; Exercise an asynchronous application response.
                                   (transport/dispatch!
                                    #(do (respond! (if (= mode "empty") {:status 204}
                                                     {:status 201 :body (if (= mode "head") PAYLOAD (:body request))}))
                                         (respond! {:status 500}))))
                                 (fn [error value] (is (nil? error)) (is (nil? value)) (completed!)))))]
            (tcp/accept! listener
                         (fn [error raw]
                           (is (nil? error))
                           (when raw
                             (keep! tcp/close! raw)
                             (if secure?
                               (keep! tls/close!
                                      (tls/accept! raw {:cert-chain [(read-fixture "server.pem") (read-fixture "ca.pem")]
                                                        :private-key (read-fixture "server-key.pem") :alpn ["http/1.1"]}
                                                   (fn [error connection]
                                                     (is (nil? error)) (when connection (serve! connection)))))
                               (serve! raw)))))
            (keep! (fixture/run-client! ["node" "test/fixtures/server-client.cjs"
                                         (str (:port (tcp/local-address listener))) mode]
                                        (fn [exit] (is (= 0 exit)) (completed!))) nil)))))))

(deftest malformed-requests-return-errors-without-entering-handler
  (when-not (is-browser?)
    (doseq [[wire status reason]
            [["GET / HTTP/1.1\r\n\r\n" 400 :invalid-host]
             ["POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 99\r\n\r\n" 413 :body-too-large]
             ["POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 0\r\nTransfer-Encoding: chunked\r\n\r\n" 400 :ambiguous-framing]
             ["POST / HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\n\r\n" 417 :unsupported-expectation]
             ["POST / HTTP/1.1\r\nHost: a\r\nContent-Encoding: gzip\r\n\r\n" 415 :unsupported-content-encoding]]]
      (with-listener!
        (fn [listener keep! finish!]
          (let [completed (atom 0) next! #(when (= 2 (swap! completed inc)) (finish!))]
            (tcp/accept! listener
                         (fn [error raw]
                           (is (nil? error))
                           (when raw
                             (keep! tcp/close! raw)
                             (http/serve! raw {:max-body-bytes 5}
                                          (fn [_ _] (is false "Rejected request reached handler"))
                                          (fn [error value]
                                            (is (= {:type :portable/http-error :operation :portable.http/serve! :reason reason}
                                                   (ex-data error)))
                                            (is (nil? value)) (next!))))))
            (keep! tcp/close!
                   (tcp/connect! (tcp/local-address listener)
                                 (fn [error client]
                                   (is (nil? error))
                                   (when client
                                     (tcp/write! client (bytes/encode-utf8 wire) (fn [error _] (is (nil? error))))
                                     (read-all! client []
                                                (fn [data]
                                                  (is (re-find (re-pattern (str "^HTTP/1.1 " status " ")) (bytes/decode-utf8 data)))
                                                  (next!)))))))))))))

(deftest handler-failures-and-invalid-responses-return-500
  (when-not (is-browser?)
    (doseq [handler [(fn [_ _] (throw (ex-info "Application failed" {})))
                     (fn [_ respond!] (respond! {:headers {"X" "bad\r\nheader"}}))]]
      (with-listener!
        (fn [listener keep! finish!]
          (let [completed (atom 0) next! #(when (= 2 (swap! completed inc)) (finish!))]
            (tcp/accept! listener
                         (fn [error raw]
                           (is (nil? error))
                           (when raw
                             (keep! tcp/close! raw)
                             (http/serve! raw {} handler
                                          (fn [error _] (is (= :handler-failed (:reason (ex-data error)))) (next!))))))
            (http/request! {:url (str "http://127.0.0.1:" (:port (tcp/local-address listener)) "/")}
                           (fn [error response] (is (nil? error)) (is (= 500 (:status response))) (next!)))))))))

(deftest serving-timeout-cancellation-and-late-responses
  (when-not (is-browser?)
    (doseq [cancel? [false true]]
      (with-listener!
        (fn [listener keep! finish!]
          (let [late (atom nil) calls (atom 0)]
            (tcp/accept! listener
                         (fn [error raw]
                           (is (nil? error))
                           (when raw
                             (keep! tcp/close! raw)
                             (let [cancel! (http/serve!
                                            raw {:timeout-ms (if cancel? 5000 100)}
                                            (fn [_ respond!] (reset! late respond!))
                                            (fn [error value]
                                              (is (= 1 (swap! calls inc)))
                                              (is (= (if cancel? :cancelled :timeout) (:reason (ex-data error))))
                                              (is (nil? value))
                                              (when-let [respond! @late] (is (nil? (respond! {:body [1]}))))
                                              (finish!)))]
                               (when cancel? (is (nil? (cancel!))) (is (nil? (cancel!))))))))
            (keep! tcp/close!
                   (tcp/connect! (tcp/local-address listener)
                                 (fn [error client]
                                   (when (nil? error)
                                     (tcp/write! client (bytes/encode-utf8 "GET / HTTP/1.1\r\nHost: a\r\n\r\n") (fn [_ _]))))))))))))

(deftest serve-validation-and-browser-rejection
  (doseq [[options handler callback reason]
          [[nil (fn [_ _]) (fn [_ _]) :expected-options-map]
           [{:unknown true} (fn [_ _]) (fn [_ _]) :unknown-option]
           [{} nil (fn [_ _]) :expected-handler]
           [{} (fn [_ _]) nil :expected-callback]
           [{:tls? :yes} (fn [_ _]) (fn [_ _]) :invalid-tls-mode]
           [{:timeout-ms 0} (fn [_ _]) (fn [_ _]) :invalid-timeout]
           [{:max-body-bytes -1} (fn [_ _]) (fn [_ _]) :invalid-body-limit]
           [{:max-response-bytes 16777217} (fn [_ _]) (fn [_ _]) :invalid-body-limit]]]
    (is (= {:type :portable/invalid-input :operation :portable.http/serve! :reason reason}
           (get-error-data http/serve! nil options handler callback))))
  (is (= (if (is-browser?) {:type :portable/unsupported-operation :operation :portable.http/serve!}
             {:type :portable/invalid-input :operation :portable.http/serve! :reason :invalid-connection})
         (get-error-data http/serve! nil {} (fn [_ _]) (fn [_ _])))))

(deftest negotiated-http2-is-rejected-before-serving
  (when-not (is-browser?)
    (let [connection (assoc (transport/create-accepted! nil (fn [])) :operation-ns "portable.tls")]
      (swap! (:state connection) assoc :tls-session {:version "TLSv1.3" :alpn "h2"})
      (is (= :unsupported-alpn (:reason (get-error-data http/serve! connection {:tls? true} (fn [_ _]) (fn [_ _])))))
      (is (= :open (:status @(:state connection))))
      (tls/close! connection))))
