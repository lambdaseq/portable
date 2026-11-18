(ns portable.crypto.internal
  "Private crypto validation and asynchronous callback completion."
  (:require [portable.bytes :as bytes]
            [portable.bytes.operation :as operation]))

(defn- throw-invalid-input [operation argument reason]
  (throw (ex-info "Invalid crypto input."
                  {:type :portable/invalid-input :operation operation :argument argument :reason reason})))

(defn- require-bytes [operation argument value]
  (when-not (vector? value) (throw-invalid-input operation argument :expected-byte-vector))
  (when (> (count value) 1048576) (throw-invalid-input operation argument :input-too-large))
  (when-not (bytes/byte-vector? value) (throw-invalid-input operation argument :expected-byte-vector)))

(defn prepare-request [kind algorithm key data callback]
  (let [operation (if (= kind :digest) :portable.crypto/digest! :portable.crypto/hmac!)
        names (case algorithm
                :sha-256 {:digest-name "SHA-256" :mac-name "HmacSHA256" :node-name "sha256" :result-size 32}
                :sha-512 {:digest-name "SHA-512" :mac-name "HmacSHA512" :node-name "sha512" :result-size 64}
                (throw-invalid-input operation :algorithm :unsupported-algorithm))]
    (when-not (fn? callback) (throw-invalid-input operation :callback :expected-callback))
    (require-bytes operation :data data)
    (when (= kind :hmac)
      (require-bytes operation :key key)
      (when (empty? key) (throw-invalid-input operation :key :empty-key)))
    (cond-> (assoc names :kind kind :operation operation :data data)
      (= kind :hmac) (assoc :key key))))

(defn throw-unsupported! [operation]
  (throw (ex-info "The native crypto API is unavailable."
                  {:type :portable/unsupported-operation :operation operation :reason :crypto-unavailable})))

(defn- make-error [operation reason cause]
  (ex-info "Crypto operation failed." {:type :portable/crypto-error :operation operation :reason reason} cause))

(defn- normalize-error [operation error]
  (if (and (= :portable/unsupported-operation (:type (ex-data error))) (= operation (:operation (ex-data error))))
    error
    (make-error operation :crypto-failure error)))

(defn start! [request callback perform!]
  (operation/start!
   request
   (fn [error value]
     (let [error (cond
                   error (normalize-error (:operation request) error)
                   (not (and (bytes/byte-vector? value) (= (:result-size request) (count value))))
                   (make-error (:operation request) :invalid-result nil)
                   :else nil)]
       (callback error (when-not error value))))
   perform!))
