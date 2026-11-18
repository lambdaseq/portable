(ns portable.crypto-test
  (:require [portable.bytes :as bytes]
            [portable.crypto :as crypto]
            [portable.bytes.operation :as operation]
            [portable.crypto-vectors :as vectors]
            #?(:clj [portable.crypto.jvm :as backend]
               :cljs [portable.crypto.javascript :as backend])
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is async]])))

(defn- run-contract! [scenario]
  #?(:clj (let [done (promise)] (scenario #(deliver done true)) (is (true? (deref done 20000 false))))
     :cljs (async done (scenario done))))

(defn- get-error [f & args]
  (try (apply f args)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error error)))

(defn- run-vectors! [kind cases]
  (run-contract!
   (fn [done]
     (letfn [(check! [remaining]
               (if-let [[algorithm example] (first remaining)]
                 (let [callback (fn [error result]
                                  (is (nil? error))
                                  (is (bytes/byte-vector? result))
                                  (when (bytes/byte-vector? result)
                                    (is (= (if (= algorithm :sha-256) 32 64) (count result)))
                                    (is (= (get example algorithm) (bytes/encode-hex result))))
                                  (check! (next remaining)))]
                   (is (nil? (if (= kind :digest)
                               (crypto/digest! algorithm (:data example) callback)
                               (crypto/hmac! algorithm (:key example) (:data example) callback)))))
                 (done)))]
       (check! (for [example cases algorithm [:sha-256 :sha-512]] [algorithm example]))))))

(deftest sha-standard-binary-padding-and-limit-vectors
  (run-vectors! :digest vectors/DIGEST_CASES))

(deftest hmac-rfc4231-binary-and-long-key-vectors
  (run-vectors! :hmac vectors/HMAC_CASES))

(deftest asynchronous-callback-and-nil-return-contract
  (run-contract!
   (fn [done]
     (let [returned (atom false)
           #?@(:clj [caller (Thread/currentThread)])
           value (crypto/digest!
                  :sha-256 []
                  (fn [error result]
                    #?(:clj (is (not (identical? caller (Thread/currentThread))))
                       :cljs (is @returned))
                    (is (nil? error)) (is (= 32 (count result))) (done)))]
       (reset! returned true)
       (is (nil? value))))))

(deftest concurrent-operations-keep-independent-engine-state
  (run-contract!
   (fn [done]
     (let [completed (atom 0)
           example (nth vectors/HMAC_CASES 1)
           digest (nth vectors/DIGEST_CASES 3)]
       (doseq [index (range 24)]
         (let [hmac? (even? index)
               algorithm (if (< (mod index 4) 2) :sha-256 :sha-512)
               expected (get (if hmac? example digest) algorithm)
               callback (fn [error result]
                          (is (nil? error))
                          (when (bytes/byte-vector? result) (is (= expected (bytes/encode-hex result))))
                          (when (= 24 (swap! completed inc)) (done)))]
           (if hmac?
             (crypto/hmac! algorithm (:key example) (:data example) callback)
             (crypto/digest! algorithm (:data digest) callback))))))))

(deftest malformed-inputs-throw-before-native-work
  (let [starts (atom 0)
        callback (fn [_ _])
        too-large (vec (repeat 1048577 0))]
    (with-redefs [backend/start! (fn [& _] (swap! starts inc))]
      (doseq [algorithm [nil :sha-1 :md5 :sha256 "SHA-256" {}]
              [f args operation] [[crypto/digest! [algorithm [] callback] :portable.crypto/digest!]
                                   [crypto/hmac! [algorithm [1] [] callback] :portable.crypto/hmac!]]]
        (is (= {:type :portable/invalid-input :operation operation :argument :algorithm :reason :unsupported-algorithm}
               (ex-data (apply get-error f args)))))
      (doseq [data [nil "abc" '(0 1) [nil] [-1] [256] [1.5] [false]
                    #?(:clj (byte-array [0 1]) :cljs (js/Uint8Array. #js [0 1]))]
              [f args operation] [[crypto/digest! [:sha-256 data callback] :portable.crypto/digest!]
                                   [crypto/hmac! [:sha-256 [1] data callback] :portable.crypto/hmac!]]]
        (is (= {:type :portable/invalid-input :operation operation :argument :data :reason :expected-byte-vector}
               (ex-data (apply get-error f args)))))
      (doseq [key [nil "key" '(1) [nil] [-1] [256] [1.5]]]
        (is (= {:type :portable/invalid-input :operation :portable.crypto/hmac! :argument :key :reason :expected-byte-vector}
               (ex-data (get-error crypto/hmac! :sha-256 key [] callback)))))
      (doseq [[f args operation argument reason]
              [[crypto/digest! [:sha-256 [] nil] :portable.crypto/digest! :callback :expected-callback]
               [crypto/hmac! [:sha-256 [1] [] nil] :portable.crypto/hmac! :callback :expected-callback]
               [crypto/hmac! [:sha-256 [] [] callback] :portable.crypto/hmac! :key :empty-key]
               [crypto/digest! [:sha-256 too-large callback] :portable.crypto/digest! :data :input-too-large]
               [crypto/hmac! [:sha-256 [1] too-large callback] :portable.crypto/hmac! :data :input-too-large]
               [crypto/hmac! [:sha-256 too-large [] callback] :portable.crypto/hmac! :key :input-too-large]]]
        (is (= {:type :portable/invalid-input :operation operation :argument argument :reason reason}
               (ex-data (apply get-error f args)))))
      (is (zero? @starts)))))

(deftest native-failures-retain-cause-and-complete-once
  (run-contract!
   (fn [done]
     (let [failure #?(:clj (Exception. "Native crypto failed.") :cljs (js/Error. "Native crypto failed."))
           completed (atom 0)]
       (doseq [[kind throw?] [[:digest true] [:hmac true] [:digest false] [:hmac false]]]
         (let [calls (atom 0)
               callback (fn [error result]
                          (is (= 1 (swap! calls inc)))
                          (is (= {:type :portable/crypto-error
                                  :operation (if (= kind :digest) :portable.crypto/digest! :portable.crypto/hmac!)
                                  :reason :crypto-failure} (ex-data error)))
                          (is (identical? failure (ex-cause error))) (is (nil? result))
                          (when (= 4 (swap! completed inc)) (done)))]
           (with-redefs [backend/start! (fn [_ complete!]
                                         (if throw? (throw failure)
                                             (do (complete! failure [1]) (complete! failure nil))))]
             (if (= kind :digest)
               (crypto/digest! :sha-256 [] callback)
               (crypto/hmac! :sha-512 [1] [] callback)))))))))

(deftest success-is-not-replaced-by-a-late-provider-error
  (run-contract!
   (fn [done]
     (let [expected (vec (repeat 32 255))
           calls (atom 0)
           failure #?(:clj (Exception. "Late provider error.") :cljs (js/Error. "Late provider error."))]
       (with-redefs [backend/start! (fn [_ complete!]
                                     (complete! nil expected)
                                     (complete! failure nil)
                                     (throw failure))]
         (crypto/digest! :sha-256 []
                         (fn [error result]
                           (is (= 1 (swap! calls inc)))
                           (is (nil? error)) (is (= expected result)) (done))))))))

(deftest malformed-provider-results-fail-without-exposing-values
  (run-contract!
   (fn [done]
     (let [completed (atom 0)]
       (doseq [value [nil [0] (vec (repeat 64 0)) (vec (repeat 32 -1)) "digest"]]
         (with-redefs [backend/start! (fn [_ complete!] (complete! nil value))]
           (crypto/digest! :sha-256 []
                           (fn [error result]
                             (is (= {:type :portable/crypto-error :operation :portable.crypto/digest! :reason :invalid-result}
                                    (ex-data error)))
                             (is (nil? result))
                             (when (= 5 (swap! completed inc)) (done))))))))))

(deftest callback-exceptions-are-outside-native-error-handling
  (let [tasks (atom [])
        failure #?(:clj (Exception. "Callback failed.") :cljs (js/Error. "Callback failed."))]
    (with-redefs [operation/dispatch! (fn [task] (swap! tasks conj task))
                  backend/start! (fn [_ complete!] (complete! nil (vec (repeat 32 0))))]
      (crypto/digest! :sha-256 [] (fn [_ _] (throw failure)))
      ((first @tasks))
      (is (= 2 (count @tasks)))
      (is (identical? failure (try ((second @tasks))
                                   (catch #?(:clj Exception :cljs :default) error error))))
      (is (= 2 (count @tasks))))))

#?(:cljs
   (defn- replace-browser-crypto! [provider]
     (let [descriptor (js/Object.getOwnPropertyDescriptor js/globalThis "crypto")]
       (js/Object.defineProperty js/globalThis "crypto" #js {:value provider :configurable true})
       (fn [] (if descriptor
                (js/Object.defineProperty js/globalThis "crypto" descriptor)
                (js/Reflect.deleteProperty js/globalThis "crypto"))))))

#?(:cljs
   (deftest missing-native-apis-reach-the-callback
     (run-contract!
      (fn [done]
        (if (= "nodejs" *target*)
          (let [provider (js/require "node:crypto")
                digest (.-createHash provider)
                hmac (.-createHmac provider)
                calls (atom 0)
                finish! (fn []
                          (when (= 2 (swap! calls inc))
                            (set! (.-createHash provider) digest)
                            (set! (.-createHmac provider) hmac)
                            (done)))]
            (set! (.-createHash provider) nil)
            (set! (.-createHmac provider) nil)
            (doseq [kind [:digest :hmac]]
              (let [callback (fn [error result]
                               (is (= {:type :portable/unsupported-operation
                                       :operation (if (= kind :digest) :portable.crypto/digest! :portable.crypto/hmac!)
                                       :reason :crypto-unavailable} (ex-data error)))
                               (is (nil? result)) (finish!))]
                (if (= kind :digest) (crypto/digest! :sha-256 [] callback) (crypto/hmac! :sha-512 [1] [] callback)))))
          (let [restore! (replace-browser-crypto! nil)
                calls (atom 0)
                finish! (fn [] (when (= 2 (swap! calls inc)) (restore!) (done)))]
            (doseq [kind [:digest :hmac]]
              (let [callback (fn [error result]
                               (is (= {:type :portable/unsupported-operation
                                       :operation (if (= kind :digest) :portable.crypto/digest! :portable.crypto/hmac!)
                                       :reason :crypto-unavailable} (ex-data error)))
                               (is (nil? result)) (finish!))]
                (if (= kind :digest) (crypto/digest! :sha-256 [] callback) (crypto/hmac! :sha-512 [1] [] callback))))))))))

#?(:cljs
   (deftest browser-promise-failures-retain-native-causes
     (when-not (= "nodejs" *target*)
       (run-contract!
        (fn [done]
          (let [failure (js/Error. "Web Crypto rejected work.")
                calls (atom 0)
                provider #js {:subtle #js {:digest (fn [& _] (js/Promise.reject failure))
                                           :importKey (fn [& _] (js/Promise.reject failure))
                                           :sign (fn [& _] (is false "Signing must wait for key import."))}}
                restore! (replace-browser-crypto! provider)
                finish! (fn [] (when (= 2 (swap! calls inc)) (restore!) (done)))]
            (doseq [kind [:digest :hmac]]
              (let [callback (fn [error result]
                               (is (= {:type :portable/crypto-error
                                       :operation (if (= kind :digest) :portable.crypto/digest! :portable.crypto/hmac!)
                                       :reason :crypto-failure} (ex-data error)))
                               (is (identical? failure (ex-cause error))) (is (nil? result)) (finish!))]
                (if (= kind :digest) (crypto/digest! :sha-256 [] callback) (crypto/hmac! :sha-512 [1] [] callback))))))))))

#?(:cljs
   (deftest browser-hmac-imports-a-private-signing-key
     (when-not (= "nodejs" *target*)
       (run-contract!
        (fn [done]
          (let [key (js-obj)
                expected (vec (repeat 32 128))
                provider #js {:subtle #js {:importKey (fn [format data algorithm extractable usages]
                                                        (is (= "raw" format)) (is (= [1 255] (vec data)))
                                                        (is (= "HMAC" (.-name algorithm))) (is (= "SHA-256" (.-hash algorithm)))
                                                        (is (false? extractable)) (is (= ["sign"] (vec usages)))
                                                        (js/Promise.resolve key))
                                           :sign (fn [algorithm imported data]
                                                   (is (= "HMAC" algorithm)) (is (identical? key imported)) (is (= [0 255] (vec data)))
                                                   (js/Promise.resolve (.-buffer (js/Uint8Array. (to-array expected)))) )}}
                restore! (replace-browser-crypto! provider)]
            (crypto/hmac! :sha-256 [1 255] [0 255]
                          (fn [error result]
                            (restore!) (is (nil? error)) (is (= expected result)) (done)))))))))
