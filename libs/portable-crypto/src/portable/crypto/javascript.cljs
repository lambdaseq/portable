(ns portable.crypto.javascript
  "Node crypto and browser Web Crypto adapters."
  (:require [portable.crypto.internal :as internal]))

(def ^:private NODE_CRYPTO
  (delay
    (when (and (= "nodejs" *target*) (exists? js/require))
      (try (js/require "node:crypto") (catch :default _ nil)))))

(defn- get-provider! [request]
  (let [native? (= "nodejs" *target*)
        provider (if native? @NODE_CRYPTO
                     (when (and (exists? js/globalThis.crypto) js/globalThis.crypto) (.-subtle js/globalThis.crypto)))
        supported? (and provider
                         (if native?
                           (if (= :digest (:kind request)) (fn? (.-createHash provider)) (fn? (.-createHmac provider)))
                           (if (= :digest (:kind request)) (fn? (.-digest provider))
                               (and (fn? (.-importKey provider)) (fn? (.-sign provider))))))]
    (when-not supported? (internal/throw-unsupported! (:operation request)))
    provider))

(defn- complete-buffer! [complete! buffer]
  (try (complete! nil (vec (js/Uint8Array. buffer)))
       (catch :default error (complete! error nil))))

(defn start! [request complete!]
  (let [provider (get-provider! request)
        data (js/Uint8Array. (to-array (:data request)))]
    (if (= "nodejs" *target*)
      (let [engine (if (= :digest (:kind request))
                     (.createHash provider (:node-name request))
                     (.createHmac provider (:node-name request) (js/Uint8Array. (to-array (:key request)))))]
        (.update engine data)
        (complete! nil (vec (.digest engine))))
      (let [result (if (= :digest (:kind request))
                     (.digest provider (:digest-name request) data)
                     (-> (.importKey provider "raw" (js/Uint8Array. (to-array (:key request)))
                                     #js {:name "HMAC" :hash (:digest-name request)} false #js ["sign"])
                         (.then (fn [key] (.sign provider "HMAC" key data)))))]
        (.then result #(complete-buffer! complete! %) #(complete! % nil)))))
  nil)
