(ns portable.clock-test
  (:require [portable.clock :as clock]
            #?(:clj [clojure.test :as test :refer [deftest is]]
               :cljs [cljs.test :as test :refer-macros [async deftest is]])))

(defn- is-finite-number? [value]
  (and (number? value)
       #?(:clj (Double/isFinite (double value))
          :cljs (js/Number.isFinite value))))

(deftest wall-time-contract
  (let [before #?(:clj (System/currentTimeMillis) :cljs (js/Date.now))
        value (clock/now-ms)
        after #?(:clj (System/currentTimeMillis) :cljs (js/Date.now))]
    (is (integer? value))
    (is (<= before value after) "Wall time uses Unix epoch milliseconds.")))

(deftest monotonic-time-contract
  (let [readings (repeatedly 256 clock/monotonic-ms)]
    (is (every? is-finite-number? readings))
    (is (apply <= readings) "Equal readings are valid on coarse clocks.")))

(defn- assert-elapsed-time! [wall-start monotonic-start]
  (let [elapsed (- (clock/monotonic-ms) monotonic-start)
        wall-elapsed (- (clock/now-ms) wall-start)]
    (is (<= 10 elapsed (+ wall-elapsed 1000))
        "A delayed reading advances in milliseconds; allow scheduler delays and coarse clocks.")))

(deftest elapsed-time-contract
  (let [wall-start (clock/now-ms)
        monotonic-start (clock/monotonic-ms)]
    #?(:clj (do
              (Thread/sleep 50)
              (assert-elapsed-time! wall-start monotonic-start))
       :cljs (async done
               (js/setTimeout
                (fn []
                  (assert-elapsed-time! wall-start monotonic-start)
                  (done))
                50)))))

#?(:cljs
   (deftest unavailable-monotonic-clock
     (let [original js/performance]
       (try
         (doseq [unavailable [#js {} js/undefined]]
           (set! js/globalThis.performance unavailable)
           (let [error (try (clock/monotonic-ms) (catch :default error error))]
             (is (= {:type :portable/unsupported-operation
                     :operation :portable.clock/monotonic-ms}
                    (ex-data error)))))
         (finally
           (set! js/globalThis.performance original))))))
