(ns portable.http-server-codec-test
  (:require [clojure.string :as str]
            [portable.http.decoder :as decoder]
            [portable.http.message :as message]
            [portable.http.server-message :as server-message]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(defn- decode-request [wire size limit]
  (let [state (decoder/create-request-decoder {:max-body-bytes limit})]
    (:request (decoder/finish (reduce #(decoder/feed %1 (vec %2)) state
                                      (partition-all size (message/encode-ascii wire)))))))

(defn- get-reason [f & args]
  (try (apply f args) (catch #?(:clj Exception :cljs :default) error (:reason (ex-data error)))))

(deftest request-framing-and-binary-payloads-under-fragmentation
  (doseq [size [1 2 7 65536]
          [wire method target body]
          [["GET /a?q=1 HTTP/1.1\r\nHost: example.test\r\n\r\n" "GET" "/a?q=1" []]
           ["POST / HTTP/1.1\r\nHost: example.test:1234\r\nContent-Length: 3\r\n\r\naÿc" "POST" "/" [97 255 99]]
           ["POST / HTTP/1.1\r\nHost: [::1]:80\r\nTransfer-Encoding: chunked\r\n\r\n2;test=yes\r\nab\r\n1\r\nc\r\n0\r\nx-trailer: ignored\r\n\r\n" "POST" "/" [97 98 99]]
           ["OPTIONS * HTTP/1.1\r\nHost: example.test\r\n\r\n" "OPTIONS" "*" []]
           ["GET / HTTP/1.0\r\n\r\n" "GET" "/" []]]]
    (let [request (decode-request wire size 3)]
      (is (= method (:method request))) (is (= target (:target request))) (is (= body (:body request))))))

(deftest request-ambiguities-and-unsupported-protocol-controls
  (doseq [[wire reason]
          [["GET / HTTP/1.1\r\n\r\n" :invalid-host]
           ["GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n" :invalid-host]
           ["GET / HTTP/1.1\r\nHost: a:65536\r\n\r\n" :invalid-host]
           ["GET / HTTP/1.1\r\nHost: a:0\r\n\r\n" :invalid-host]
           ["POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n" :ambiguous-framing]
           ["POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3,3\r\n\r\n" :ambiguous-framing]
           ["POST / HTTP/1.1\r\nHost: a\r\nContent-Length: -1\r\n\r\n" :malformed-response]
           ["POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 3\r\n\r\nab" :incomplete-response]
           ["GET / HTTP/1.1\r\nHost: a\r\n\r\nGET / HTTP/1.1\r\nHost: a\r\n\r\n" :unexpected-data]
           ["POST / HTTP/1.0\r\nTransfer-Encoding: chunked\r\n\r\n" :unsupported-transfer-encoding]
           ["POST / HTTP/1.1\r\nHost: a\r\nExpect: 100-continue\r\n\r\n" :unsupported-expectation]
           ["POST / HTTP/1.1\r\nHost: a\r\nContent-Encoding: gzip\r\n\r\n" :unsupported-content-encoding]
           ["GET / HTTP/1.1\r\nHost: a\r\nUpgrade: websocket\r\n\r\n" :unsupported-upgrade]
           ["GET http://a/ HTTP/1.1\r\nHost: a\r\n\r\n" :unsupported-target]
           ["GET /a#frag HTTP/1.1\r\nHost: a\r\n\r\n" :unsupported-target]
           ["GET /%zz HTTP/1.1\r\nHost: a\r\n\r\n" :unsupported-target]
           ["GET / HTTP/2.0\r\nHost: a\r\n\r\n" :malformed-request]
           ["GET / HTTP/1.1\r\nHost: a\r\n folded: value\r\n\r\n" :malformed-request]
           ["POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nHost: b\r\n\r\n" :invalid-trailer]]]
    (is (= reason (get-reason decode-request wire 65536 100)) wire)))

(deftest request-resource-limits
  (doseq [wire ["POST / HTTP/1.1\r\nHost: a\r\nContent-Length: 4\r\n\r\n1234"
                "POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n4\r\n1234\r\n0\r\n\r\n"]]
    (is (= :body-too-large (get-reason decode-request wire 1 3))))
  (is (= :target-too-long (get-reason decode-request (str "GET /" (apply str (repeat 8192 "x")) " HTTP/1.1\r\nHost: a\r\n\r\n") 65536 0)))
  (is (= :headers-too-large (get-reason decode-request (str "GET / HTTP/1.1\r\nX: " (apply str (repeat 65536 "x"))) 65536 0)))
  (is (= :too-many-headers (get-reason decode-request (str "GET / HTTP/1.1\r\n" (apply str (repeat 101 "X: a\r\n")) "\r\n") 65536 0))))

(deftest response-framing-head-and-no-content-statuses
  (let [encode #(message/decode-latin1 (server-message/encode-response %1 %2 100))]
    (is (= "HTTP/1.1 200 Response\r\nconnection: close\r\ncontent-length: 3\r\nx-test: yes\r\n\r\nabc"
           (encode {} {:body [97 98 99] :headers {"X-Test" "yes"}})))
    (is (str/ends-with? (encode {:method "HEAD"} {:body [97 98 99]}) "content-length: 3\r\n\r\n"))
    (doseq [status [204 304]]
      (is (not (str/includes? (encode {} {:status status}) "content-length"))))
    (is (str/starts-with? (encode {:version "1.0"} {}) "HTTP/1.0")))
  (doseq [[response reason]
          [[{:status 101} :invalid-status] [{:status "200"} :invalid-status]
           [{:body [-1]} :expected-byte-vector] [{:body [1 2]} :response-too-large]
           [{:status 204 :body [1]} :body-not-allowed] [{:status 205 :body [1]} :body-not-allowed]
           [{:headers {"Content-Length" "0"}} :managed-header]
           [{:headers {"X" "a\r\nb: c"}} :invalid-headers]
           [{:headers {"X" "a" "x" "b"}} :invalid-headers]
           [{:typo true} :unknown-option]]]
    (is (= reason (get-reason server-message/encode-response {} response 1)))))
