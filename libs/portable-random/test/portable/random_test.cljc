(ns portable.random-test
  (:require [portable.bytes :as bytes]
            [portable.random :as random]
            [portable.random.sampling :as sampling]
            [portable.random.source :as source]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is]])))

(defn- get-error [f & args]
  (try (apply f args)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error error)))

(deftest secure-byte-vectors-and-quota-boundaries
  (doseq [amount [0 1 16 255 256 65535 65536 65537 131073 1048576]]
    (let [data (random/generate-bytes! amount)]
      (is (bytes/byte-vector? data))
      (is (= amount (count data)))))
  (let [original (random/generate-bytes! 32)
        changed (assoc original 0 (mod (inc (first original)) 256))]
    (is (not= original changed))
    (is (= (subvec original 1) (subvec changed 1)))))

(deftest bounded-integers-cover-safe-integer-range
  (doseq [upper-bound [1 2 3 7 255 256 257 65535 65536 65537 2147483647 2147483648
                       4294967295 4294967296 4294967297 281474976710656 9007199254740990 9007199254740991]]
    (let [values (repeatedly 32 #(random/generate-int! upper-bound))]
      (is (every? integer? values))
      (is (every? #(<= 0 % (dec upper-bound)) values))))
  (is (= 0 (random/generate-int! 1))))

(deftest every-accepted-small-integer-has-equal-preimages
  (doseq [upper-bound (range 2 257)]
    (let [{:keys [mask]} (sampling/create-plan upper-bound)
          candidates (map #(sampling/decode-sample [%] mask) (range 256))
          counts (frequencies (filter #(< % upper-bound) candidates))]
      (is (= (set (range upper-bound)) (set (keys counts))))
      (is (= 1 (count (set (vals counts)))))))
  (let [{:keys [mask]} (sampling/create-plan 257)
        candidates (map #(sampling/decode-sample [(quot % 256) (mod % 256)] mask) (range 65536))
        counts (frequencies (filter #(< % 257) candidates))]
    (is (= (set (range 257)) (set (keys counts))))
    (is (= #{128} (set (vals counts))))))

(deftest rejection-sampling-retries-and-preserves-large-integers
  (doseq [[upper-bound draws expected]
          [[3 [[255] [3] [2]] 2]
           [65536 [[255 255]] 65535]
           [4294967297 [[255 255 255 255 255] [1 0 0 0 0]] 4294967296]
           [9007199254740991 [[255 255 255 255 255 255 255] [31 255 255 255 255 255 254]] 9007199254740990]]]
    (let [position (atom 0)]
      (with-redefs [source/fill-bytes! (fn [_ amount]
                                       (let [data (nth draws @position)]
                                         (swap! position inc) (is (= amount (count data))) data))]
        (is (= expected (random/generate-int! upper-bound))))
      (is (= (count draws) @position))))
  (let [reads (atom 0)]
    (with-redefs [source/fill-bytes! (fn [_ _] (swap! reads inc) [])]
      (is (= 0 (random/generate-int! 1))))
    (is (zero? @reads))))

(deftest repeated-rejection-fails-instead-of-hanging-or-adding-bias
  (let [reads (atom 0)]
    (with-redefs [source/fill-bytes! (fn [_ _] (swap! reads inc) [255])]
      (is (= {:type :portable/random-error :operation :portable.random/generate-int! :reason :rejection-limit}
             (ex-data (get-error random/generate-int! 3)))))
    (is (= 128 @reads))))

(deftest invalid-input-is-rejected-before-source-access
  (with-redefs [source/get-source! (fn [] (throw #?(:clj (Exception. "Source must not be accessed.")
                                                  :cljs (js/Error. "Source must not be accessed."))))]
    (doseq [amount [nil false true [] {} "8" :bytes -1 1.5 1048577
                    #?(:clj Double/NaN :cljs js/NaN) #?(:clj Double/POSITIVE_INFINITY :cljs js/Infinity)]]
      (is (= {:type :portable/invalid-input :operation :portable.random/generate-bytes! :reason :invalid-byte-count}
             (ex-data (get-error random/generate-bytes! amount)))))
    (doseq [upper-bound [nil false true [] {} "8" :bound -1 0 1.5 9007199254740992
                         #?(:clj Double/NaN :cljs js/NaN) #?(:clj Double/POSITIVE_INFINITY :cljs js/Infinity)]]
      (is (= {:type :portable/invalid-input :operation :portable.random/generate-int! :reason :invalid-upper-bound}
             (ex-data (get-error random/generate-int! upper-bound)))))))

(deftest unavailable-secure-sources-fail-for-both-operations
  (let [reads (atom 0)]
    (with-redefs [source/get-source! (fn [] nil)
                  source/fill-bytes! (fn [_ _] (swap! reads inc) [])]
      (doseq [[generate argument operation] [[random/generate-bytes! 0 :portable.random/generate-bytes!]
                                            [random/generate-bytes! 16 :portable.random/generate-bytes!]
                                            [random/generate-int! 1 :portable.random/generate-int!]
                                            [random/generate-int! 3 :portable.random/generate-int!]]]
        (is (= {:type :portable/unsupported-operation :operation operation :reason :secure-random-unavailable}
               (ex-data (get-error generate argument))))))
    (is (zero? @reads))))

(deftest native-failures-retain-cause-and-requested-operation
  (let [failure #?(:clj (Exception. "Entropy failed.") :cljs (js/Error. "Entropy failed."))]
    (doseq [[generate argument operation] [[random/generate-bytes! 16 :portable.random/generate-bytes!]
                                          [random/generate-int! 3 :portable.random/generate-int!]]]
      (with-redefs [source/fill-bytes! (fn [_ _] (throw failure))]
        (let [error (get-error generate argument)]
          (is (= {:type :portable/random-error :operation operation :reason :entropy-failure} (ex-data error)))
          (is (identical? failure (ex-cause error)))))
      (with-redefs [source/get-source! (fn [] (throw failure))]
        (let [error (get-error generate argument)]
          (is (= {:type :portable/random-error :operation operation :reason :entropy-failure} (ex-data error)))
          (is (identical? failure (ex-cause error))))))))

#?(:cljs
   (deftest javascript-provider-availability-is-checked
     (if (= "nodejs" *target*)
       (let [crypto (js/require "node:crypto")
             original (.-randomFillSync crypto)]
         (try
           (set! (.-randomFillSync crypto) nil)
           (is (= :secure-random-unavailable (:reason (ex-data (get-error random/generate-bytes! 1)))))
           (is (= :secure-random-unavailable (:reason (ex-data (get-error random/generate-int! 2)))))
           (finally (set! (.-randomFillSync crypto) original))))
       (let [descriptor (js/Object.getOwnPropertyDescriptor js/globalThis "crypto")]
         (try
           (js/Object.defineProperty js/globalThis "crypto" #js {:value nil :configurable true})
           (is (= :secure-random-unavailable (:reason (ex-data (get-error random/generate-bytes! 1)))))
           (is (= :secure-random-unavailable (:reason (ex-data (get-error random/generate-int! 2)))))
           (finally
             (if descriptor
               (js/Object.defineProperty js/globalThis "crypto" descriptor)
               (js/Reflect.deleteProperty js/globalThis "crypto"))))))))

#?(:cljs
   (deftest browser-fills-quota-sized-views-and-discards-partial-failures
     (when-not (= "nodejs" *target*)
       (let [sizes (atom [])
             provider #js {:getRandomValues (fn [buffer]
                                              (swap! sizes conj (.-length buffer))
                                              (.fill buffer (count @sizes))
                                              buffer)}]
         (with-redefs [source/get-source! (fn [] provider)]
           (let [data (random/generate-bytes! 131073)]
             (is (= [65536 65536 1] @sizes))
             (is (every? #(= 1 %) (subvec data 0 65536)))
             (is (every? #(= 2 %) (subvec data 65536 131072)))
             (is (= 3 (peek data))))))
       (let [calls (atom 0)
             failure (js/Error. "Second fill failed.")
             provider #js {:getRandomValues (fn [buffer]
                                              (when (= 2 (swap! calls inc)) (throw failure))
                                              (.fill buffer 255))}]
         (with-redefs [source/get-source! (fn [] provider)]
           (let [error (get-error random/generate-bytes! 65537)]
             (is (= {:type :portable/random-error :operation :portable.random/generate-bytes! :reason :entropy-failure}
                    (ex-data error)))
             (is (identical? failure (ex-cause error)))
             (is (= 2 @calls))))))))
