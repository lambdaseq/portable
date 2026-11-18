(ns portable.http-codec-test
  (:require [clojure.string :as str]
            [portable.http.decoder :as decoder]
            [portable.http.message :as message]
            #?(:clj [clojure.test :as test :refer [deftest is]]
               :cljs [cljs.test :as test :refer-macros [deftest is]])))

(defn- decode-response [wire method limit fragment]
  (let [state (decoder/create-decoder {:method method :max-body-bytes limit})
        received (reduce #(decoder/feed %1 (vec %2)) state
                         (partition-all fragment (message/encode-ascii wire)))]
    (:response (decoder/finish received))))

(defn- get-error-data [f & args]
  (try (apply f args)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error (ex-data error))))

(deftest response-framing-under-fragmentation
  (doseq [fragment [1 2 7 64 65536]
          [wire method expected]
          [["HTTP/1.1 200 OK\r\nContent-Length: 3\r\nX-Test: a\r\nX-Test: b\r\n\r\nabc" "GET" [97 98 99]]
           ["HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2;ext=yes\r\nab\r\n1\r\nc\r\n0\r\nx-end: done\r\n\r\n" "GET" [97 98 99]]
           ["HTTP/1.0 200 OK\r\n\r\nabc" "GET" [97 98 99]]
           ["HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 103 Early Hints\r\nLink: </x>\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabc" "GET" [97 98 99]]
           ["HTTP/1.1 204 No Content\r\n\r\n" "GET" []]
           ["HTTP/1.1 304 Not Modified\r\nContent-Length: 1234\r\n\r\n" "GET" []]
           ["HTTP/1.1 200 OK\r\nContent-Length: 1234\r\n\r\n" "HEAD" []]
           ["HTTP/1.1 503 Unavailable\r\nContent-Length: 0\r\n\r\n" "GET" []]]]
    (is (= expected (:body (decode-response wire method 100 fragment))))))

(deftest header-values-and-trailers
  (is (= ["a" "b"]
         (get-in (decode-response "HTTP/1.1 200 OK\r\nX-Test: a\r\nx-test: b\r\nContent-Length: 0\r\n\r\n" "GET" 0 1)
                 [:headers "x-test"])))
  (is (= ["ÿ"]
         (get-in (decode-response "HTTP/1.1 200 OK\r\nX-Test: ÿ\r\nContent-Length: 0\r\n\r\n" "GET" 0 1)
                 [:headers "x-test"])))
  (is (not (contains? (:headers (decode-response "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nx-trailer: ignored\r\n\r\n" "GET" 0 1))
                       "x-trailer"))))

(deftest invalid-response-framing
  (doseq [[wire reason]
          [["HTTP/1.1 200 OK\r\nContent-Length: 3\r\nTransfer-Encoding: chunked\r\n\r\n" :ambiguous-framing]
           ["HTTP/1.1 200 OK\r\nContent-Length: 3\r\nContent-Length: 3\r\n\r\nabc" :ambiguous-framing]
           ["HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n" :malformed-response]
           ["HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nabc" :incomplete-response]
           ["HTTP/1.1 200 OK\r\nContent-Length: 3\r\n\r\nabcd" :unexpected-data]
           ["HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip, chunked\r\n\r\n" :unsupported-transfer-encoding]
           ["HTTP/1.1 200 OK\r\nContent-Encoding: br\r\nContent-Length: 3\r\n\r\nabc" :unsupported-content-encoding]
           ["HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nZ\r\n" :malformed-response]
           ["HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabcXX" :malformed-response]
           ["HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nab" :incomplete-response]
           ["HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n0\r\nContent-Length: 1\r\n\r\n" :invalid-trailer]
           ["HTTP/1.1 200 OK\r\n folded: bad\r\n\r\n" :malformed-response]
           ["HTTP/1.1 200 OK\r\nBad Header: value\r\n\r\n" :malformed-response]
           ["HTTP/2.0 200 OK\r\n\r\n" :malformed-response]
           ["HTTP/1.1 101 Switching Protocols\r\n\r\n" :unsupported-upgrade]
           ["HTTP/1.1 100 Continue\r\nContent-Length: 0\r\n\r\n" :malformed-response]
           [(str (str/join (repeat 9 "HTTP/1.1 100 Continue\r\n\r\n")) "HTTP/1.1 204 Empty\r\n\r\n") :too-many-informational-responses]
           ["HTTP/1.1 302 Found\r\nLocation: /elsewhere\r\nContent-Length: 0\r\n\r\n" :redirect-blocked]]]
    (is (= reason (:reason (get-error-data decode-response wire "GET" 100 65536))))))

(deftest response-resource-limits
  (doseq [limit (range 16)]
    (let [chunk-size #?(:clj (Integer/toHexString (inc limit)) :cljs (.toString (inc limit) 16))
          state (decoder/create-decoder {:method "GET" :max-body-bytes limit})
          wire (str "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n" chunk-size "\r\n")]
      (is (= :body-too-large (:reason (get-error-data decoder/feed state (message/encode-ascii wire)))))))
  (doseq [wire ["HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nabcd"
                "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nabcd\r\n0\r\n\r\n"
                "HTTP/1.0 200 OK\r\n\r\nabcd"]]
    (is (= :body-too-large (:reason (get-error-data decode-response wire "GET" 3 1)))))
  (is (= :headers-too-large
         (:reason (get-error-data decode-response (str "HTTP/1.1 200 OK\r\nX: " (str/join (repeat 65536 "a"))) "GET" 0 70000))))
  (is (= :too-many-headers
         (:reason (get-error-data decode-response (str "HTTP/1.1 200 OK\r\n" (str/join (repeat 101 "X: a\r\n")) "\r\n") "GET" 0 70000)))))

(deftest head-redirects-are-rejected
  (is (= :redirect-blocked
         (:reason (get-error-data decode-response
                                  "HTTP/1.1 302 Found\r\nLocation: /elsewhere\r\nContent-Length: 0\r\n\r\n"
                                  "HEAD" 0 1)))))

(deftest request-serialization
  (let [request (message/prepare-request {:url "http://example.test/a?q=1#fragment" :headers {"X-Test" "ok"}} (fn [_ _]))]
    (is (= "GET /a?q=1 HTTP/1.1\r\naccept-encoding: gzip, deflate\r\nconnection: close\r\nhost: example.test\r\nx-test: ok\r\n\r\n"
           (message/decode-latin1 (message/encode-request request)))))
  (let [request (message/prepare-request {:url "https://[::1]:8443?x=1" :method :post :body [0 255]} (fn [_ _]))
        wire (message/encode-request request)]
    (is (= "::1" (:host request)))
    (is (= 8443 (:port request)))
    (is (str/includes? (message/decode-latin1 wire) "content-length: 2\r\n"))
    (is (= [0 255] (subvec wire (- (count wire) 2))))))

(deftest invalid-request-inputs
  (doseq [[options reason]
          [[{} :invalid-url]
           [{:url "ftp://example.test/"} :invalid-url]
           [{:url "http://user:password@example.test/"} :invalid-url]
           [{:url "http://example.test/a\r\nb"} :invalid-url]
           [{:url "http://example.test/a b"} :invalid-url]
           [{:url "http://example.test/λ"} :invalid-url]
           [{:url "http://example.test/%zz"} :invalid-url]
           [{:url "http://example.test/a/../b"} :invalid-url]
           [{:url "http://example.test/%2e%2e/b"} :invalid-url]
           [{:url "http://example.test:0/"} :invalid-url]
           [{:url "http://example.test:65536/"} :invalid-url]
           [{:url "http://example.test/" :headers {"X" "a\r\nb: c"}} :invalid-headers]
           [{:url "http://example.test/" :headers {"X" "a" "x" "b"}} :invalid-headers]
           [{:url "http://example.test/" :headers {"Content-Length" "0"}} :managed-header]
           [{:url "http://example.test/" :method :connect} :invalid-method]
           [{:url "http://example.test/" :body [1]} :body-not-allowed]
           [{:url "http://example.test/" :method :post :body [-1]} :expected-byte-vector]
           [{:url "http://example.test/" :max-body-bytes -1} :invalid-body-limit]
           [{:url "http://example.test/" :max-encoded-body-bytes -1} :invalid-encoded-body-limit]
           [{:url "http://example.test/" :max-encoded-body-bytes 16777217} :invalid-encoded-body-limit]
           [{:url "http://example.test/" :timeout-ms 0} :invalid-timeout]
           [{:url "http://example.test/" :ca-certs []} :invalid-tls-options]
           [{:url "http://example.test/" :follow-redirects true} :unknown-option]]]
    (is (= {:type :portable/invalid-input :operation :portable.http/request! :reason reason}
           (get-error-data message/prepare-request options (fn [_ _]))))))

(deftest content-coding-framing-and-limits
  (doseq [coding ["GZip" "gzip, deflate" "identity, gzip" " gzip "]]
    (let [state (decoder/create-decoder {:method "GET" :max-body-bytes 0 :max-encoded-body-bytes 3})
          result (decoder/feed state (message/encode-ascii
                                      (str "HTTP/1.1 200 OK\r\nContent-Encoding: " coding "\r\nContent-Length: 3\r\n\r\nabc")))]
      (is (= [97 98 99] (get-in result [:response :body])))
      (is (seq (:content-codings result)))))
  (doseq [coding ["" "gzip," "gzip,,deflate" "gzip;level=1" "br" "gzip,gzip,gzip,gzip,gzip"]]
    (is (= :unsupported-content-encoding
           (:reason (get-error-data message/parse-content-codings {"content-encoding" [coding]})))))
  (doseq [wire ["HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Length: 3\r\n\r\nabc"
                "HTTP/1.1 200 OK\r\nContent-Encoding: deflate\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n0\r\n\r\n"
                "HTTP/1.0 200 OK\r\nContent-Encoding: gzip\r\n\r\nabc"]]
    (is (= :body-too-large
           (:reason (get-error-data decoder/feed
                                    (decoder/create-decoder {:method "GET" :max-body-bytes 100 :max-encoded-body-bytes 2})
                                    (message/encode-ascii wire))))))
  (doseq [[status method] [[200 "HEAD"] [204 "GET"] [304 "GET"]]]
    (is (= [] (:body (decode-response (str "HTTP/1.1 " status " Empty\r\nContent-Encoding: gzip\r\nContent-Length: 123\r\n\r\n")
                                      method 0 1))))))
