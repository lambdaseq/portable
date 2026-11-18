(ns portable.compression.internal
  "Private validation, bounded results, and asynchronous completion."
  (:require [portable.bytes :as bytes]
            [portable.bytes.operation :as operation]))

(defn- throw-invalid-input [operation argument reason]
  (throw (ex-info "Invalid compression input."
                  {:type :portable/invalid-input :operation operation :argument argument :reason reason})))

(defn prepare-request [kind format data options callback]
  (let [operation (if (= kind :compress) :portable.compression/compress! :portable.compression/decompress!)]
    (when-not (#{:gzip :deflate} format) (throw-invalid-input operation :format :unsupported-format))
    (when-not (fn? callback) (throw-invalid-input operation :callback :expected-callback))
    (when-not (vector? data) (throw-invalid-input operation :data :expected-byte-vector))
    (when (> (count data) 16777216) (throw-invalid-input operation :data :input-too-large))
    (when-not (bytes/byte-vector? data) (throw-invalid-input operation :data :expected-byte-vector))
    (when-not (map? options) (throw-invalid-input operation :options :expected-map))
    (when (seq (remove #{:max-output-bytes} (keys options)))
      (throw-invalid-input operation :options :unknown-option))
    (let [limit (:max-output-bytes options 1048576)]
      (when-not (and (integer? limit) (<= 0 limit 16777216))
        (throw-invalid-input operation :max-output-bytes :out-of-range))
      {:kind kind :operation operation :format format :data data :max-output-bytes limit})))

(defn make-error [request reason cause]
  (ex-info "Compression operation failed."
           {:type :portable/compression-error :operation (:operation request) :reason reason} cause))

(defn throw-failure! [request reason]
  (throw (make-error request reason nil)))

(defn throw-unsupported! [request]
  (throw (ex-info "The native compression API is unavailable."
                  {:type :portable/unsupported-operation :operation (:operation request)
                   :reason :compression-unavailable})))

(defn- normalize-error [request error]
  (if (and (#{:portable/compression-error :portable/unsupported-operation} (:type (ex-data error)))
           (= (:operation request) (:operation (ex-data error))))
    error
    (make-error request (if (= :decompress (:kind request)) :invalid-data :compression-failure) error)))

(defn start! [request callback perform!]
  (operation/start!
   request
   (fn [error value]
     (let [error (cond
                   error (normalize-error request error)
                   (not (bytes/byte-vector? value)) (make-error request :invalid-result nil)
                   (> (count value) (:max-output-bytes request)) (make-error request :output-too-large nil)
                   :else nil)]
       (callback error (when-not error value))))
   perform!))
