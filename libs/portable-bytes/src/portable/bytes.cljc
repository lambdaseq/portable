(ns portable.bytes
  "Strict UTF-8, Base64, and hex conversions using vectors of unsigned bytes."
  (:require [clojure.string :as str]
            #?(:cljs [goog.crypt.base64 :as base64]))
  #?(:clj (:import [java.nio ByteBuffer CharBuffer]
                   [java.nio.charset CharacterCodingException StandardCharsets]
                   [java.util Base64])))

(defn- throw-invalid-input [operation reason]
  (throw (ex-info "Invalid byte conversion input."
                  {:type :portable/invalid-input :operation operation :reason reason})))

(defn- require-string [operation value]
  (when-not (string? value)
    (throw-invalid-input operation :expected-string)))

(defn byte-vector?
  "Return whether a value is an immutable vector of unsigned byte integers."
  [value]
  (and (vector? value)
       (every? #(and (integer? %) (<= 0 % 255)) value)))

(defn- require-bytes [operation value]
  (when-not (byte-vector? value)
    (throw-invalid-input operation :expected-byte-vector)))

#?(:cljs
   (defn- require-text-decoder [operation]
     (when-not (exists? js/TextDecoder)
       (throw (ex-info "UTF-8 decoding is unavailable on this JavaScript host."
                       {:type :portable/unsupported-operation :operation operation})))))

(defn encode-utf8
  "Encode a Unicode string as an unsigned byte vector. Reject unpaired surrogates."
  [value]
  (require-string :portable.bytes/encode-utf8 value)
  #?(:clj
     (try
       (let [buffer (.encode (.newEncoder StandardCharsets/UTF_8) (CharBuffer/wrap ^String value))
             data (byte-array (.remaining buffer))]
         (.get buffer data)
         (mapv #(bit-and % 255) data))
       (catch CharacterCodingException _
         (throw-invalid-input :portable.bytes/encode-utf8 :invalid-unicode)))
     :cljs
     (do
       (require-text-decoder :portable.bytes/encode-utf8)
       (when-not (exists? js/TextEncoder)
         (throw (ex-info "UTF-8 encoding is unavailable on this JavaScript host."
                         {:type :portable/unsupported-operation :operation :portable.bytes/encode-utf8})))
       (let [data (.encode (js/TextEncoder.) value)
             decoded (.decode (js/TextDecoder. "utf-8" #js {:fatal true :ignoreBOM true}) data)]
         ;; TextEncoder replaces unpaired surrogates; reject that lossy conversion.
         (when-not (= value decoded)
           (throw-invalid-input :portable.bytes/encode-utf8 :invalid-unicode))
         (vec data)))
     :default
     (throw (ex-info "UTF-8 encoding is unsupported on this runtime."
                     {:type :portable/unsupported-operation :operation :portable.bytes/encode-utf8}))))

(defn decode-utf8
  "Decode an unsigned byte vector as UTF-8. Preserve BOMs and reject malformed bytes."
  [value]
  (require-bytes :portable.bytes/decode-utf8 value)
  #?(:clj
     (try
       (str (.decode (.newDecoder StandardCharsets/UTF_8)
                     (ByteBuffer/wrap (byte-array (map unchecked-byte value)))))
       (catch CharacterCodingException _
         (throw-invalid-input :portable.bytes/decode-utf8 :invalid-utf8)))
     :cljs
     (do
       (require-text-decoder :portable.bytes/decode-utf8)
       (try
         (.decode (js/TextDecoder. "utf-8" #js {:fatal true :ignoreBOM true})
                  (js/Uint8Array. (to-array value)))
         (catch js/TypeError _
           (throw-invalid-input :portable.bytes/decode-utf8 :invalid-utf8))))
     :default
     (throw (ex-info "UTF-8 decoding is unsupported on this runtime."
                     {:type :portable/unsupported-operation :operation :portable.bytes/decode-utf8}))))

(defn encode-base64
  "Encode an unsigned byte vector as standard, padded RFC 4648 Base64."
  [value]
  (require-bytes :portable.bytes/encode-base64 value)
  #?(:clj (.encodeToString (Base64/getEncoder) (byte-array (map unchecked-byte value)))
     :cljs (base64/encodeByteArray (to-array value))
     :default (throw (ex-info "Base64 encoding is unsupported on this runtime."
                              {:type :portable/unsupported-operation :operation :portable.bytes/encode-base64}))))

(defn decode-base64
  "Decode canonical padded Base64. Reject whitespace, URL-safe text, and nonzero pad bits."
  [value]
  (require-string :portable.bytes/decode-base64 value)
  (when-not (and (zero? (mod (count value) 4))
                 (re-matches #"[A-Za-z0-9+/]*={0,2}" value))
    (throw-invalid-input :portable.bytes/decode-base64 :invalid-base64))
  (let [data #?(:clj (mapv #(bit-and % 255) (.decode (Base64/getDecoder) ^String value))
                :cljs (vec (base64/decodeStringToByteArray value))
                :default (throw (ex-info "Base64 decoding is unsupported on this runtime."
                                         {:type :portable/unsupported-operation :operation :portable.bytes/decode-base64})))]
    (when-not (= value (encode-base64 data))
      (throw-invalid-input :portable.bytes/decode-base64 :invalid-base64))
    data))

(def ^:private HEX_DIGITS "0123456789abcdef")

(defn encode-hex
  "Encode an unsigned byte vector as lowercase hex, with two digits per byte."
  [value]
  (require-bytes :portable.bytes/encode-hex value)
  (str/join (map #(str (nth HEX_DIGITS (quot % 16)) (nth HEX_DIGITS (mod % 16))) value)))

(defn decode-hex
  "Decode even-length hex in either case. Reject whitespace and prefixes."
  [value]
  (require-string :portable.bytes/decode-hex value)
  (when-not (and (even? (count value)) (re-matches #"[0-9a-fA-F]*" value))
    (throw-invalid-input :portable.bytes/decode-hex :invalid-hex))
  (mapv (fn [[high low]]
          (+ (* 16 (str/index-of HEX_DIGITS (str/lower-case (str high))))
             (str/index-of HEX_DIGITS (str/lower-case (str low)))))
        (partition 2 value)))
