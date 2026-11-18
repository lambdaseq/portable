(ns portable.random.source
  "Private platform entropy sources."
  #?(:clj (:import [java.security SecureRandom])))

#?(:clj (def ^:private RANDOM (delay (SecureRandom.))))
#?(:cljs
   (def ^:private NODE_CRYPTO
     (delay
       (when (and (= "nodejs" *target*) (exists? js/require))
         (try (js/require "node:crypto") (catch :default _ nil))))))

(defn get-source! []
  #?(:clj @RANDOM
     :cljs
     (if (= "nodejs" *target*)
       (let [source @NODE_CRYPTO]
         (when (and source (fn? (.-randomFillSync source))) source))
       (when (exists? js/globalThis.crypto)
         (let [source js/globalThis.crypto]
           (when (and source (fn? (.-getRandomValues source))) source))))
     :default nil))

(defn fill-bytes! [source amount]
  #?(:clj
     (let [buffer (byte-array amount)]
       (when (pos? amount) (.nextBytes ^SecureRandom source buffer))
       (mapv #(bit-and % 255) buffer))
     :cljs
     (let [buffer (js/Uint8Array. amount)]
       (if (= "nodejs" *target*)
         (when (pos? amount) (.randomFillSync source buffer))
         ;; Web Crypto limits each call to 65536 bytes; views share the full output buffer.
         (loop [offset 0]
           (when (< offset amount)
             (let [end (min amount (+ offset 65536))]
               (.getRandomValues source (.subarray buffer offset end))
               (recur end)))))
       (vec buffer))))
