(ns portable.compression.javascript
  "Node zlib and browser Compression Streams adapters."
  (:require [portable.compression.framing :as framing]
            [portable.compression.internal :as internal]))

(def ^:private NODE_ZLIB
  (delay (when (and (= "nodejs" *target*) (exists? js/require))
           (try (js/require "node:zlib") (catch :default _ nil)))))

(defn- start-node! [request complete!]
  (let [provider @NODE_ZLIB
        decompress? (= :decompress (:kind request))
        method (if decompress? (if (= :gzip (:format request)) "inflateRaw" "inflate")
                   (if (= :gzip (:format request)) "gzip" "deflate"))
        perform (when provider (aget provider method))
        input (if decompress? (:payload request) (:data request))]
    (when-not (fn? perform) (internal/throw-unsupported! request))
    (.call perform provider (js/Uint8Array. (to-array input))
           #js {:info true :maxOutputLength (inc (:max-output-bytes request))}
           (fn [error result]
             (try
               (cond
                 error (complete! (internal/make-error request
                                                       (if (= "ERR_BUFFER_TOO_LARGE" (.-code error)) :output-too-large
                                                           (if decompress? :invalid-data :compression-failure)) error) nil)
                 (and decompress? (not= (count input) (.-bytesWritten (.-engine result))))
                 (complete! (internal/make-error request :invalid-data nil) nil)
                 (> (.-length (.-buffer result)) (:max-output-bytes request))
                 (complete! (internal/make-error request :output-too-large nil) nil)
                 :else (complete! nil (framing/verify-result request (vec (.-buffer result)))))
               (catch :default error (complete! error nil)))))))

(defn- ignore-rejection! [promise]
  (.catch promise (fn [_] nil)))

(defn- start-browser! [request complete!]
  (let [constructor (if (= :compress (:kind request))
                      (when (exists? js/CompressionStream) js/CompressionStream)
                      (when (exists? js/DecompressionStream) js/DecompressionStream))]
    (when-not constructor (internal/throw-unsupported! request))
    (let [stream (js/Reflect.construct constructor #js [(name (:format request))])
          reader (.getReader (.-readable stream))
          writer (.getWriter (.-writable stream))
          completed (atom false)
          finish! (fn [error data]
                    (when (compare-and-set! completed false true)
                      (when error
                        (ignore-rejection! (.cancel reader))
                        (ignore-rejection! (.abort writer)))
                      (complete! error data)))]
      (letfn [(read-next! [received]
                (when-not @completed
                  (-> (.read reader)
                      (.then (fn [result]
                               (cond
                                 (.-done result) (finish! nil (framing/verify-result request received))
                                 (> (+ (count received) (.-length (.-value result))) (:max-output-bytes request))
                                 (finish! (internal/make-error request :output-too-large nil) nil)
                                 :else (read-next! (into received (.-value result))))))
                      (.catch #(finish! % nil)))))]
        ;; Drain concurrently so writable backpressure cannot deadlock the operation.
        (read-next! [])
        (-> (.write writer (js/Uint8Array. (to-array (:data request))))
            (.then (fn [_] (.close writer)))
            (.catch #(finish! % nil)))))))

(defn start! [request complete!]
  (let [prepared (if (= :decompress (:kind request)) (framing/prepare-decompression request) request)]
    (if (= "nodejs" *target*) (start-node! prepared complete!) (start-browser! prepared complete!)))
  nil)
