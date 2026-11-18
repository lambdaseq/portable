(ns portable.compression.jvm
  "Bounded JVM and Babashka DEFLATE engines."
  (:require [portable.compression.framing :as framing]
            [portable.compression.internal :as internal])
  (:import [java.io ByteArrayOutputStream]
           [java.util.zip Deflater Inflater]))

(defn- inflate-bytes [request]
  (let [engine (Inflater. (= :gzip (:format request)))
        output (ByteArrayOutputStream.)
        buffer (byte-array 8192)
        limit (:max-output-bytes request)]
    (try
      (.setInput engine (byte-array (map unchecked-byte (:payload request))))
      (loop []
        (let [size (.inflate engine buffer 0 (min 8192 (inc (- limit (.size output)))))]
          (when (> (+ (.size output) size) limit) (internal/throw-failure! request :output-too-large))
          (.write output buffer 0 size)
          (cond
            (.finished engine)
            (do (when-not (zero? (.getRemaining engine)) (internal/throw-failure! request :invalid-data))
                (mapv #(bit-and % 255) (.toByteArray output)))
            (.needsDictionary engine) (internal/throw-failure! request :dictionary-required)
            (or (.needsInput engine) (zero? size)) (internal/throw-failure! request :invalid-data)
            :else (recur))))
      (finally (.end engine)))))

(defn- deflate-bytes [request]
  (let [gzip? (= :gzip (:format request))
        limit (- (:max-output-bytes request) (if gzip? 18 0))]
    (when (neg? limit) (internal/throw-failure! request :output-too-large))
    (let [engine (Deflater. -1 gzip?)
          output (ByteArrayOutputStream.)
          buffer (byte-array 8192)]
      (try
        (.setInput engine (byte-array (map unchecked-byte (:data request))))
        (.finish engine)
        (loop []
          (let [size (.deflate engine buffer 0 (min 8192 (inc (- limit (.size output)))))]
            (when (> (+ (.size output) size) limit) (internal/throw-failure! request :output-too-large))
            (.write output buffer 0 size)
            (if (.finished engine)
              (let [compressed (mapv #(bit-and % 255) (.toByteArray output))]
                (if gzip?
                  (into (into (into [31 139 8 0 0 0 0 0 0 255] compressed)
                              (framing/encode-le32 (framing/crc32 (:data request))))
                        (framing/encode-le32 (count (:data request))))
                  compressed))
              (recur))))
        (finally (.end engine))))))

(defn start! [request complete!]
  (if (= :decompress (:kind request))
    (let [prepared (framing/prepare-decompression request)]
      (complete! nil (framing/verify-result prepared (inflate-bytes prepared))))
    (complete! nil (deflate-bytes request))))
