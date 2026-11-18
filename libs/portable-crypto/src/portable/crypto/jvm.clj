(ns portable.crypto.jvm
  "JVM and Babashka cryptographic primitives, with one engine per operation."
  (:import [java.security MessageDigest]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(defn start! [request complete!]
  (let [data (byte-array (map unchecked-byte (:data request)))
        result (case (:kind request)
                 :digest (.digest ^MessageDigest (MessageDigest/getInstance (:digest-name request)) data)
                 :hmac (let [engine (Mac/getInstance (:mac-name request))
                             key (SecretKeySpec. (byte-array (map unchecked-byte (:key request))) (:mac-name request))]
                         (.init ^Mac engine key)
                         (.doFinal ^Mac engine data)))]
    (complete! nil (mapv #(bit-and % 255) result))))
