(ns portable.compression.framing
  "Private gzip/zlib framing and gzip checksum validation."
  (:require [portable.compression.internal :as internal]))

(def ^:private CRC_TABLE
  (mapv (fn [value]
          (loop [crc value bits 8]
            (if (zero? bits) crc
                (recur (bit-xor (unsigned-bit-shift-right crc 1) (if (odd? crc) 3988292384 0)) (dec bits)))))
        (range 256)))

(defn crc32 [data]
  (let [crc (bit-xor 4294967295
                     (reduce (fn [crc byte]
                               (bit-xor (unsigned-bit-shift-right crc 8)
                                        (nth CRC_TABLE (bit-and (bit-xor crc byte) 255))))
                             4294967295 data))]
    #?(:clj (bit-and crc 4294967295) :cljs (unsigned-bit-shift-right crc 0))))

(defn encode-le32 [value]
  (mapv #(bit-and (unsigned-bit-shift-right value %) 255) [0 8 16 24]))

(defn- read-le32 [data index]
  (reduce + (map-indexed (fn [offset byte] (* byte (nth [1 256 65536 16777216] offset)))
                         (subvec data index (+ index 4)))))

(defn- require-header [request end]
  (when (or (> end (- (count (:data request)) 8)) (> end 65536))
    (internal/throw-failure! request :invalid-data)))

(defn- skip-string [request start]
  (loop [index start]
    (require-header request (inc index))
    (if (zero? (nth (:data request) index)) (inc index) (recur (inc index)))))

(defn- gzip-payload [request]
  (let [data (:data request)]
    (require-header request 10)
    (when-not (and (= [31 139 8] (subvec data 0 3)) (zero? (bit-and (nth data 3) 224)))
      (internal/throw-failure! request :invalid-data))
    (let [flags (nth data 3)
          extra-end (if (bit-test flags 2)
                      (do (require-header request 12)
                          (+ 12 (nth data 10) (* 256 (nth data 11))))
                      10)
          _ (require-header request extra-end)
          name-end (if (bit-test flags 3) (skip-string request extra-end) extra-end)
          comment-end (if (bit-test flags 4) (skip-string request name-end) name-end)
          header-end (+ comment-end (if (bit-test flags 1) 2 0))
          footer (- (count data) 8)]
      (require-header request header-end)
      (when (and (bit-test flags 1)
                 (not= (bit-and (crc32 (subvec data 0 comment-end)) 65535)
                       (+ (nth data comment-end) (* 256 (nth data (inc comment-end))))))
        (internal/throw-failure! request :invalid-data))
      (assoc request :payload (subvec data header-end footer)
                     :expected-crc (read-le32 data footer) :expected-size (read-le32 data (+ footer 4))))))

(defn prepare-decompression [request]
  (if (= :gzip (:format request))
    (gzip-payload request)
    (let [data (:data request)]
      (when (< (count data) 6) (internal/throw-failure! request :invalid-data))
      (let [[cmf flags] data]
        (when-not (and (= (bit-and cmf 15) 8) (<= (unsigned-bit-shift-right cmf 4) 7)
                       (zero? (mod (+ (* 256 cmf) flags) 31)))
          (internal/throw-failure! request :invalid-data))
        (when (bit-test flags 5) (internal/throw-failure! request :dictionary-required)))
      (assoc request :payload data))))

(defn verify-result [request data]
  (when (and (= :decompress (:kind request)) (= :gzip (:format request))
             (or (not= (:expected-size request) (count data)) (not= (:expected-crc request) (crc32 data))))
    (internal/throw-failure! request :invalid-data))
  data)
