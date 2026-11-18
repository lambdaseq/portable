(ns portable.compression
  "Asynchronous gzip and zlib-wrapped DEFLATE over unsigned byte vectors."
  (:require [portable.compression.internal :as internal]
            #?(:clj [portable.compression.jvm :as backend]
               :cljs [portable.compression.javascript :as backend])))

(defn compress!
  "Compress with :gzip or :deflate (zlib wrapper). Callback: (error byte-vector). Return nil.
  Options: :max-output-bytes, default 1048576, maximum 16777216. Input maximum: 16777216.
  Invalid arguments throw synchronously; native failures reach the callback asynchronously."
  ([format data callback] (compress! format data {} callback))
  ([format data options callback]
   (internal/start! (internal/prepare-request :compress format data options callback) callback backend/start!)))

(defn decompress!
  "Decode one :gzip member or one :deflate zlib stream. Reject trailing data and dictionaries.
  Options: :max-output-bytes, default 1048576, maximum 16777216. Input maximum: 16777216.
  Callback: (error byte-vector), asynchronously, once. Return nil. Invalid arguments throw."
  ([format data callback] (decompress! format data {} callback))
  ([format data options callback]
   (internal/start! (internal/prepare-request :decompress format data options callback) callback backend/start!)))
