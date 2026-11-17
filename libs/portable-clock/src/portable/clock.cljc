(ns portable.clock
  "Wall time and elapsed time in milliseconds, without host objects.")

(defn now-ms
  "Read integer milliseconds since 1970-01-01T00:00:00Z.
  Wall time can jump after clock adjustments. Use monotonic-ms for durations."
  []
  #?(:clj (System/currentTimeMillis)
     :cljs (js/Date.now)
     :default (throw (ex-info "Wall clock is unsupported on this runtime."
                              {:type :portable/unsupported-operation
                               :operation :portable.clock/now-ms}))))

(defn monotonic-ms
  "Read nondecreasing milliseconds from an unspecified origin; fractions are allowed.
  Subtract readings within the same running context to measure elapsed time.
  Resolution and behavior during system sleep depend on the host."
  []
  #?(:clj (/ (double (System/nanoTime)) 1000000.0)
     :cljs (if (and (exists? js/performance) (fn? (.-now js/performance)))
             (.now js/performance)
             (throw (ex-info "Monotonic clock is unavailable on this JavaScript host."
                             {:type :portable/unsupported-operation
                              :operation :portable.clock/monotonic-ms})))
     :default (throw (ex-info "Monotonic clock is unsupported on this runtime."
                              {:type :portable/unsupported-operation
                               :operation :portable.clock/monotonic-ms}))))
