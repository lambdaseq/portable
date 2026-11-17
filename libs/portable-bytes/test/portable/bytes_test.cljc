(ns portable.bytes-test
  (:require [portable.bytes :as bytes]
            #?(:clj [clojure.test :as test :refer [deftest is testing]]
               :cljs [cljs.test :as test :refer-macros [deftest is testing]])))

(defn- get-error-data [convert value]
  (try (convert value)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
         (ex-data error))))

(deftest byte-vector-predicate
  (doseq [value [[] [0] [255] (vec (range 256))]]
    (is (true? (bytes/byte-vector? value))))
  (doseq [value [nil '(0) "" [-1] [256] [1.5] [nil] [false]]]
    (is (false? (bytes/byte-vector? value)))))

(deftest utf8-examples
  (doseq [[text data] [["" []]
                       ["hello" [104 101 108 108 111]]
                       ["\u0000" [0]]
                       ["é" [195 169]]
                       ["λ" [206 187]]
                       ["中" [228 184 173]]
                       ["😀" [240 159 152 128]]
                       ["\uDBFF\uDFFF" [244 143 191 191]]
                       ["\uFEFFa" [239 187 191 97]]
                       ["a\uFEFF" [97 239 187 191]]
                       ["e\u0301" [101 204 129]]
                       ["\uFFFD" [239 191 189]]]]
    (is (= data (bytes/encode-utf8 text)))
    (is (= text (bytes/decode-utf8 data)))))

(deftest malformed-utf8
  (doseq [data [[128] [192 128] [193 191] [194] [194 65] [226 130]
                [224 128 128] [237 160 128] [240 128 128 128]
                [244 144 128 128] [245 128 128 128] [255] [239 187]]]
    (is (= {:type :portable/invalid-input :operation :portable.bytes/decode-utf8 :reason :invalid-utf8}
           (get-error-data bytes/decode-utf8 data)))))

(deftest malformed-unicode
  (doseq [units [[0xD800] [0xDC00] [0xD800 65] [65 0xDC00] [0xD800 0xD800]]]
    (let [text #?(:clj (apply str (map char units))
                  :cljs (.apply js/String.fromCharCode nil (to-array units)))]
      (is (= {:type :portable/invalid-input :operation :portable.bytes/encode-utf8 :reason :invalid-unicode}
             (get-error-data bytes/encode-utf8 text))))))

(deftest base64-rfc4648-examples
  (doseq [[text encoded] [["" ""] ["f" "Zg=="] ["fo" "Zm8="] ["foo" "Zm9v"]
                          ["foob" "Zm9vYg=="] ["fooba" "Zm9vYmE="] ["foobar" "Zm9vYmFy"]]]
    (let [data (bytes/encode-utf8 text)]
      (is (= encoded (bytes/encode-base64 data)))
      (is (= data (bytes/decode-base64 encoded)))))
  (is (= "+/8=" (bytes/encode-base64 [251 255])))
  (is (= [251 255] (bytes/decode-base64 "+/8="))))

(deftest malformed-base64
  (doseq [encoded ["Zg" "Zg=" "Zg===" "Zg==\n" " Zg==" "Z g==" "====" "A==="
                   "Zg==AA==" "-_8=" "!!!!" "Zh==" "Zm9=" "a" "Zm9v\u0000"]]
    (is (= {:type :portable/invalid-input :operation :portable.bytes/decode-base64 :reason :invalid-base64}
           (get-error-data bytes/decode-base64 encoded)))))

(deftest hex-examples
  (is (= "" (bytes/encode-hex [])))
  (is (= [] (bytes/decode-hex "")))
  (is (= "00010f107f80ff" (bytes/encode-hex [0 1 15 16 127 128 255])))
  (is (= [0 1 15 16 127 128 255] (bytes/decode-hex "00010F107f80Ff"))))

(deftest malformed-hex
  (doseq [encoded ["0" "abc" "gg" "0x00" "00 " " 00" "00\n" "00-ff" "ＦＦ"]]
    (is (= {:type :portable/invalid-input :operation :portable.bytes/decode-hex :reason :invalid-hex}
           (get-error-data bytes/decode-hex encoded)))))

(deftest invalid-input-shapes
  (doseq [[convert operation] [[bytes/encode-utf8 :portable.bytes/encode-utf8]
                               [bytes/decode-base64 :portable.bytes/decode-base64]
                               [bytes/decode-hex :portable.bytes/decode-hex]]
          value [nil 0 [] {} :text]]
    (is (= {:type :portable/invalid-input :operation operation :reason :expected-string}
           (get-error-data convert value))))
  (doseq [[convert operation] [[bytes/decode-utf8 :portable.bytes/decode-utf8]
                               [bytes/encode-base64 :portable.bytes/encode-base64]
                               [bytes/encode-hex :portable.bytes/encode-hex]]
          value [nil "" {} '(0 1) [nil] [-1] [256] [1.5] ["0"] [false]]]
    (is (= {:type :portable/invalid-input :operation operation :reason :expected-byte-vector}
           (get-error-data convert value)))))

(deftest byte-roundtrips
  (doseq [size [0 1 2 3 255 256 257 70000]
          [encode decode] [[bytes/encode-base64 bytes/decode-base64]
                           [bytes/encode-hex bytes/decode-hex]]]
    (testing (str "unsigned byte roundtrip, length " size)
      (let [data (vec (take size (cycle (range 256))))
            result (decode (encode data))]
        (is (vector? result))
        (is (= data result))))))

(deftest unicode-roundtrips
  (doseq [text [(str "prefix" "\u0000" "éλ中😀" "\uFEFF" "suffix")
                (apply str (repeat 10000 "éλ中😀"))]]
    (is (= text (bytes/decode-utf8 (bytes/encode-utf8 text))))))

(deftest native-byte-arrays-are-not-public-values
  (let [data #?(:clj (byte-array [0 1]) :cljs (js/Uint8Array. #js [0 1]))]
    (doseq [convert [bytes/decode-utf8 bytes/encode-base64 bytes/encode-hex]]
      (is (= :expected-byte-vector (:reason (get-error-data convert data)))))))

#?(:cljs
   (deftest unavailable-text-codecs
     (let [encoder js/TextEncoder
           decoder js/TextDecoder]
       (try
         (set! js/globalThis.TextEncoder js/undefined)
         (is (= {:type :portable/unsupported-operation :operation :portable.bytes/encode-utf8}
                (get-error-data bytes/encode-utf8 "text")))
         (set! js/globalThis.TextEncoder encoder)
         (set! js/globalThis.TextDecoder js/undefined)
         (is (= {:type :portable/unsupported-operation :operation :portable.bytes/encode-utf8}
                (get-error-data bytes/encode-utf8 "text")))
         (is (= {:type :portable/unsupported-operation :operation :portable.bytes/decode-utf8}
                (get-error-data bytes/decode-utf8 [])))
         (finally
           (set! js/globalThis.TextEncoder encoder)
           (set! js/globalThis.TextDecoder decoder))))))
