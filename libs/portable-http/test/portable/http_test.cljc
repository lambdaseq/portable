(ns portable.http-test
  (:require [clojure.string :as str]
            [portable.bytes :as bytes]
            [portable.compression :as compression]
            [portable.http.transport :as transport]
            [portable.http.message :as message]
            [portable.tcp.operation :as control]
            [portable.http :as http]
            [portable.http-codec-test]
            [portable.http-fixture :as fixture]
            #?(:clj [clojure.test :as test :refer [deftest is]]
               :cljs [cljs.test :as test :refer-macros [deftest is async]])))

(defn- run-contract! [scenario]
  #?(:clj (let [finished (promise)]
            (scenario #(deliver finished :done))
            (is (= :done (deref finished 20000 :timeout))))
     :cljs (async done (scenario done))))

(defn- get-error-data [f & args]
  (try (apply f args)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error (ex-data error))))

(deftest shared-http-request-contract
  (run-contract!
   (fn [done]
     (fixture/start!
      (fn [{:keys [url raw-url stop!]}]
        (let [cases [[(str url "/get") {} 200 fixture/PAYLOAD]
                     [(str url "/echo") {:method :post :headers {"X-Test" "yes"} :body [0 255 1 2]} 201 [0 255 1 2]]
                     [(str url "/gzip") {:max-body-bytes 5} 200 fixture/PAYLOAD]
                     [(str url "/deflate") {:max-body-bytes 5} 200 fixture/PAYLOAD]
                     [(str url "/stacked") {:max-body-bytes 5} 200 fixture/PAYLOAD]
                     [(str url "/gzip-empty") {:max-body-bytes 0} 200 []]
                     [(str url "/gzip") {:method :head :max-body-bytes 0} 200 []]
                     [(str raw-url "/gzip-chunked") {} 200 fixture/PAYLOAD]
                     [(str raw-url "/gzip-close") {} 200 fixture/PAYLOAD]
                     [(str url "/head") {:method :head :max-body-bytes 0} 200 []]
                     [(str url "/empty") {:max-body-bytes 0} 204 []]
                     [(str url "/not-modified") {:max-body-bytes 0} 304 []]
                     [(str url "/error") {} 503 (bytes/encode-utf8 "failed")]
                     [(str raw-url "/fixed") {} 200 [97 98 99]]
                     [(str raw-url "/chunked") {} 200 [97 98 99]]
                     [(str raw-url "/interim") {} 200 [97 98 99]]
                     [(str raw-url "/close") {} 200 [97 98 99]]]]
          (letfn [(check! [remaining]
                    (if-let [[target options status body] (first remaining)]
                      (http/request!
                       (merge {:url target :timeout-ms 5000} options)
                       (fn [error response]
                         (is (nil? error))
                         (is (= status (:status response)))
                         (is (= body (:body response)))
                         (is (map? (:headers response)))
                         (check! (next remaining))))
                      (stop! done)))]
            (check! cases))))))))

(deftest response-limits-redirects-and-truncation
  (run-contract!
   (fn [done]
     (fixture/start!
      (fn [{:keys [url raw-url stop!]}]
        (let [cases [[(str url "/large") {:max-body-bytes 10} :body-too-large]
                     [(str url "/gzip-large") {:max-body-bytes 10} :body-too-large]
                     [(str url "/gzip") {:max-body-bytes 4} :body-too-large]
                     [(str url "/deflate") {:max-body-bytes 4} :body-too-large]
                     [(str url "/redirect") {} (if (fixture/is-browser?) :network-error :redirect-blocked)]
                     [(str url "/redirect") {:method :head} (if (fixture/is-browser?) :network-error :redirect-blocked)]
                     [(str raw-url "/truncated") {} (if (fixture/is-browser?) :network-error :incomplete-response)]
                     [(str raw-url "/ambiguous") {} :ambiguous-framing]
                     [(str raw-url "/badchunk") {} (if (fixture/is-browser?) :network-error :malformed-response)]]]
          (letfn [(check! [remaining]
                    (if-let [[target options reason] (first remaining)]
                      (http/request!
                       (merge {:url target :timeout-ms 5000} options)
                       (fn [error value]
                         (is (= {:type :portable/http-error :operation :portable.http/request! :reason reason}
                                (ex-data error)))
                         (is (nil? value))
                         (check! (next remaining))))
                      (stop! done)))]
            (check! cases))))))))

(deftest deadlines-and-idempotent-cancellation
  (run-contract!
   (fn [done]
     (fixture/start!
      (fn [{:keys [url stop!]}]
        (http/request!
         {:url (str url "/slow-body") :timeout-ms 100}
         (fn [error value]
           (is (= :timeout (:reason (ex-data error)))) (is (nil? value))
           (let [calls (atom 0)
                 cancel! (http/request!
                          {:url (str url "/slow") :timeout-ms 5000}
                          (fn [error value]
                            (is (= 1 (swap! calls inc)))
                            (is (= :cancelled (:reason (ex-data error))))
                            (is (nil? value))
                            (stop! done)))]
             (is (nil? (cancel!)))
             (is (nil? (cancel!)))))))))))

(deftest headers-and-runtime-decoding-limits
  (run-contract!
   (fn [done]
     (fixture/start!
      (fn [{:keys [url stop!]}]
        (http/request!
         {:url (str url "/repeat")}
         (fn [error response]
           (is (nil? error))
           (is (= "a, b" (str/join ", " (get-in response [:headers "x-repeat"]))))
           (http/request!
            {:url (str url "/gzip")}
            (fn [error response]
              (is (nil? error)) (is (= fixture/PAYLOAD (:body response)))
              (is (= ["gzip"] (get-in response [:headers "content-encoding"])))
              (stop! done))))))))))

(deftest verified-https-and-browser-trust-limit
  (run-contract!
   (fn [done]
     (fixture/start!
      (fn [{:keys [tls-url stop!]}]
        (let [options {:url (str tls-url "/get") :ca-certs [(fixture/read-ca)] :timeout-ms 5000}]
          (if (fixture/is-browser?)
            (do
              (is (= {:type :portable/unsupported-operation :operation :portable.http/request!
                      :reason :custom-trust-unavailable}
                     (get-error-data http/request! options (fn [_ _]))))
              (stop! done))
            (http/request!
             options
             (fn [error response]
               (is (nil? error))
               (is (= fixture/PAYLOAD (:body response)))
               (http/request!
                (dissoc options :ca-certs)
                (fn [error response]
                  (is (= :certificate-rejected (:reason (ex-data error))))
                  (is (nil? response))
                  (stop! done))))))))))))

(deftest browser-managed-headers-are-explicit
  (when (fixture/is-browser?)
    (doseq [headers [{"Cookie" "secret"} {"X-HTTP-Method-Override" "TRACE"}]]
      (is (= :unsupported-header
             (:reason (get-error-data http/request! {:url "http://example.test/" :headers headers}
                                      (fn [_ _]))))))))

#?(:cljs
   (deftest node-uses-portable-protocol-stack
     (when-not (fixture/is-browser?)
       (run-contract!
        (fn [done]
          (fixture/start!
           (fn [{:keys [url stop!]}]
             (let [original js/fetch]
               (set! js/globalThis.fetch (fn [& _] (throw (js/Error. "Node must use portable HTTP/1.1."))))
               (http/request! {:url (str url "/get")}
                              (fn [error response]
                                (try
                                  (is (nil? error))
                                  (is (= fixture/PAYLOAD (:body response)))
                                  (finally
                                    (set! js/globalThis.fetch original)
                                    (stop! done)))))))))))))

(deftest compressed-response-validation-respects-runtime-control
  (run-contract!
   (fn [done]
     (fixture/start!
      (fn [{:keys [url stop!]}]
        (letfn [(check! [remaining]
                  (if-let [path (first remaining)]
                    (http/request!
                     {:url (str url path)}
                     (fn [error response]
                       (if (fixture/is-browser?)
                         ;; Fetch owns validation and can accept incomplete or corrupt gzip footers.
                         (if error
                           (do (is (= :network-error (:reason (ex-data error)))) (is (nil? response)))
                           (is (= fixture/PAYLOAD (:body response))))
                         (do (is (= :invalid-compressed-body (:reason (ex-data error)))) (is (nil? response))))
                       (check! (next remaining))))
                    (stop! done)))]
          (check! ["/gzip-corrupt" "/gzip-truncated"])))))))

(deftest encoded-size-controls-are-explicit
  (run-contract!
   (fn [done]
     (fixture/start!
      (fn [{:keys [url stop!]}]
        (if (fixture/is-browser?)
          (do (is (= :encoded-limit-unavailable
                     (:reason (get-error-data http/request! {:url (str url "/gzip") :max-encoded-body-bytes 10}
                                              (fn [_ _])))))
              (stop! done))
          (letfn [(check! [remaining]
                    (if-let [[path limit] (first remaining)]
                      (http/request! {:url (str url path) :max-encoded-body-bytes limit}
                                     (fn [error response]
                                       (is (= :body-too-large (:reason (ex-data error))))
                                       (is (nil? response)) (check! (next remaining))))
                      (stop! done)))]
            (check! [["/gzip" 10] ["/get" 4] ["/stacked" 10]]))))))))

(deftest decoding-keeps-the-whole-request-deadline-and-cancellation
  (run-contract!
   (fn [done]
     (let [pending (atom nil) calls (atom 0)
           operation (control/create!
                      100
                      (fn [error response]
                        (is (= 1 (swap! calls inc)))
                        (is (= :timeout (:reason (ex-data error)))) (is (nil? response))
                        (@pending nil [1])
                        (is (= 1 @calls))
                        (let [pending (atom nil)
                              operation (control/create!
                                         5000
                                         (fn [error response]
                                           (is (= :cancelled (:reason (ex-data error)))) (is (nil? response))
                                           (@pending nil [1]) (done)) message/make-error)]
                          (with-redefs [compression/decompress! (fn [_ _ _ callback] (reset! pending callback))]
                            (transport/decode-body! {:max-body-bytes 1 :max-encoded-body-bytes 100}
                                                    operation {:status 200 :headers {} :body []} [:gzip]))
                          (control/cancel! operation))) message/make-error)]
       (with-redefs [compression/decompress! (fn [_ _ _ callback] (reset! pending callback))]
         (transport/decode-body! {:max-body-bytes 1 :max-encoded-body-bytes 100}
                                 operation {:status 200 :headers {} :body []} [:gzip]))))))
