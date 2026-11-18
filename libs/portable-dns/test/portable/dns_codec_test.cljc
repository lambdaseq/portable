(ns portable.dns-codec-test
  (:require [clojure.string :as str]
            [portable.dns :as dns]
            [portable.dns-examples :as examples]
            [portable.dns.framing :as framing]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(defn- get-error-data [f & args]
  (try (apply f args)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error (ex-data error))))

(deftest query-wire-and-presentation-names
  (is (= examples/QUERY (dns/encode-query {:id 4660 :name "EXAMPLE.test."})))
  (is (= [{:name "example.test." :type :a :class 1}] (:questions (dns/decode-message examples/QUERY))))
  (is (false? (:response? (dns/decode-message examples/QUERY))))
  (doseq [[input expected] [["." "."] ["_sip._tcp.Example.test" "_sip._tcp.example.test."]
                            ["a\\.b.test" "a\\046b.test."] ["a\\092b.test." "a\\092b.test."]
                            ["\\000\\255.test" "\\000\\255.test."]]]
    (let [wire (dns/encode-query {:id 65535 :name input :type :aaaa :recursion-desired? false})
          decoded (dns/decode-message wire)]
      (is (= expected (get-in decoded [:questions 0 :name])))
      (is (= :aaaa (get-in decoded [:questions 0 :type])))
      (is (false? (:recursion-desired? decoded)))))
  (let [name (str/join "." [(str/join (repeat 63 "a")) (str/join (repeat 63 "b"))
                             (str/join (repeat 63 "c")) (str/join (repeat 61 "d"))])]
    (is (= (str name ".") (get-in (dns/decode-message (dns/encode-query {:id 0 :name name})) [:questions 0 :name])))))

(deftest records-sections-and-unsigned-values
  (let [decoded (dns/decode-message (examples/message examples/ALL_RECORDS [(examples/record 6 examples/SOA_DATA)]
                                                     [(examples/record 1 [203 0 113 2])] 34176))
        answers (:answers decoded)]
    (is (= 4660 (:id decoded)))
    (is (:response? decoded))
    (is (:authoritative? decoded))
    (is (:recursion-available? decoded))
    (is (= 0 (:rcode decoded)))
    (is (= [{:address "192.0.2.1"} {:address "2001:db8::1"} {:name "alias.example.test."}
            {:name "ns.example.test."} {:name "ptr.example.test."}
            {:preference 10 :exchange "mail.example.test."} {:strings [[0 255 65] []]}
            {:primary "ns.example.test." :mailbox "hostmaster.example.test." :serial 4294967295
             :refresh 3600 :retry 600 :expire 86400 :minimum 60}
            {:priority 10 :weight 20 :port 443 :target "service.example.test."}
            {:bytes [192 12 255 0]} {:bytes [0 255]}]
           (mapv :data answers)))
    (is (= 65000 (:type (nth answers 9))))
    (is (= 3 (:class (nth answers 10))))
    (is (= 4294967295 (:ttl (nth answers 10))))
    (is (= 1 (count (:authorities decoded))))
    (is (= "203.0.113.2" (get-in decoded [:additionals 0 :data :address])))))

(deftest ipv6-zero-compression-is-deterministic
  (doseq [[wire expected] [[(vec (repeat 16 0)) "::"]
                           [[0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 1] "::1"]
                           [[0 1 0 0 0 0 0 2 0 0 0 0 0 3 0 4] "1::2:0:0:3:4"]
                           [[0 1 0 2 0 3 0 4 0 5 0 6 0 0 0 0] "1:2:3:4:5:6::"]]]
    (is (= expected (get-in (dns/decode-message (examples/message [(examples/record 28 wire)])) [:answers 0 :data :address])))))

(deftest backward-compression-chains-and-rdata-targets
  (let [first-record (examples/record 5 [5 97 108 105 97 115 192 12])
        start (+ 12 (count examples/QUESTION))
        target (+ start 12)
        later (into (examples/pointer target) (drop 2 (examples/record 1 [192 0 2 2])))
        chain (into (examples/pointer start) (drop 2 (examples/record 1 [192 0 2 3])))
        decoded (dns/decode-message (examples/message [first-record later chain]))]
    (is (= ["example.test." "alias.example.test." "example.test."] (mapv :name (:answers decoded))))))

(deftest malformed-messages-and-compression-are-bounded
  (doseq [length (range (count examples/A_RESPONSE))]
    (is (= :portable/dns-error (:type (get-error-data dns/decode-message (subvec examples/A_RESPONSE 0 length))))))
  (doseq [name [[192 12] [192 14] [192 0] [1 97 192 12] [64 0]]]
    (let [wire (vec (concat [0 1 1 0 0 1 0 0 0 0 0 0] name [0 1 0 1]))]
      (is (#{:invalid-compression :invalid-name} (:reason (get-error-data dns/decode-message wire))))))
  (is (= :invalid-compression
         (:reason (get-error-data dns/decode-message (examples/message [(into [192 13] (drop 2 (examples/record 1 [1 2 3 4])))])))))
  (is (= :invalid-compression
         (:reason (get-error-data dns/decode-message (examples/message [(examples/record 33 [0 0 0 0 0 53 192 12])])))))
  (is (= :trailing-data (:reason (get-error-data dns/decode-message (conj examples/A_RESPONSE 0)))))
  (is (= :reserved-flags (:reason (get-error-data dns/decode-message (assoc examples/QUERY 3 64)))))
  (is (= :too-many-records (:reason (get-error-data dns/decode-message (assoc examples/QUERY 6 4 7 0)))))
  (is (= :message-too-large (:reason (get-error-data dns/decode-message (vec (repeat 65536 0)))))))

(deftest malformed-record-data-is-rejected
  (doseq [record [(examples/record 1 [1 2 3]) (examples/record 28 (vec (repeat 15 0)))
                  (examples/record 16 []) (examples/record 16 [4 1]) (examples/record 15 [0 1])
                  (examples/record 5 [192 12 0]) (examples/record 6 [192 12 192 12])
                  (examples/record 33 [0 0 0 0 0 1])]]
    (is (#{:invalid-rdata :incomplete-message} (:reason (get-error-data dns/decode-message (examples/message [record])))))))

(deftest mutated-packets-return-data-or-structured-errors
  (doseq [offset (range (count examples/A_RESPONSE))
          value [0 63 64 192 255]]
    (let [result (try {:message (dns/decode-message (assoc examples/A_RESPONSE offset value))}
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error {:error error}))]
      (is (or (map? (:message result)) (= :portable/dns-error (:type (ex-data (:error result)))))))))

(deftest input-validation-on-every-runtime
  (doseq [[options reason] [[nil :expected-options-map] [{} :invalid-id]
                            [{:id -1 :name "test"} :invalid-id] [{:id 65536 :name "test"} :invalid-id]
                            [{:id 1 :name "test" :type :bogus} :invalid-type]
                            [{:id 1 :name "test" :type 252} :invalid-type]
                            [{:id 1 :name "test" :recursion-desired? nil} :invalid-recursion-flag]
                            [{:id 1 :name "test" :class 3} :unknown-option]]]
    (is (= {:type :portable/invalid-input :operation :portable.dns/encode-query :reason reason}
           (get-error-data dns/encode-query options))))
  (doseq [name [nil "" "a..test" "é.test" "a\\" "a\\999" "a\\12" "a b"
                (str/join (repeat 64 "a"))
                (str/join "." (repeat 4 (str/join (repeat 63 "a"))))]]
    (is (= :invalid-name (:reason (get-error-data dns/encode-query {:id 0 :name name})))))
  (doseq [data [nil '(0 1) [-1] [256] [1.5] "message"]]
    (is (= :expected-byte-vector (:reason (get-error-data dns/decode-message data)))))
  (let [decoded (dns/decode-message (dns/encode-query {:id 0 :name "test" :type 65000}))]
    (is (= 65000 (get-in decoded [:questions 0 :type])))))

(deftest tcp-length-framing-under-fragmentation
  (doseq [size [1 2 3 7 64 65536]]
    (let [wire (framing/frame-message examples/A_RESPONSE)
          state (reduce #(framing/feed %1 (vec %2)) (framing/create-decoder 65535) (partition-all size wire))]
      (is (= examples/A_RESPONSE (:message (framing/finish state))))))
  (doseq [[data limit reason] [[[0 11] 65535 :invalid-frame-length] [[255 255] 100 :message-too-large]
                              [(conj (framing/frame-message examples/A_RESPONSE) 0) 65535 :unexpected-data]]]
    (is (= reason (:reason (get-error-data framing/feed (framing/create-decoder limit) data)))))
  (is (= :incomplete-response (:reason (get-error-data framing/finish (framing/feed (framing/create-decoder 65535) [0]))))))
