(ns portable.crypto
  "Asynchronous SHA-256/SHA-512 digests and HMACs over unsigned byte vectors."
  (:require [portable.crypto.internal :as internal]
            #?(:clj [portable.crypto.jvm :as backend]
               :cljs [portable.crypto.javascript :as backend])))

(defn digest!
  "Digest a byte vector with :sha-256 or :sha-512. Callback: (error byte-vector). Return nil.
  Input errors throw synchronously; native failures and unsupported APIs reach the callback."
  [algorithm data callback]
  (internal/start! (internal/prepare-request :digest algorithm nil data callback) callback backend/start!))

(defn hmac!
  "Compute HMAC with :sha-256 or :sha-512 and a nonempty byte-vector key.
  Callback: (error byte-vector). Input vectors are each limited to 1048576 bytes. Return nil."
  [algorithm key data callback]
  (internal/start! (internal/prepare-request :hmac algorithm key data callback) callback backend/start!))
