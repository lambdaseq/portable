(ns portable.dns.entropy
  "Private native transaction-ID generation."
  #?(:clj (:import [java.security SecureRandom])))

#?(:clj (def ^:private RANDOM (SecureRandom.)))
#?(:cljs
   (def ^:private CRYPTO
     (when (and (= "nodejs" *target*) (not (exists? js/window))
                (not (exists? js/importScripts)) (exists? js/require))
       (js/require "node:crypto"))))

(defn generate-id! []
  #?(:clj (.nextInt ^SecureRandom RANDOM 65536)
     :cljs (.randomInt CRYPTO 65536)))
