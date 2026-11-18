(ns portable.random
  "Synchronous secure random byte vectors and uniform bounded integers."
  (:require [portable.random.sampling :as sampling]
            [portable.random.source :as source]))

(defn- throw-invalid-input [operation reason]
  (throw (ex-info "Invalid random input." {:type :portable/invalid-input :operation operation :reason reason})))

(defn- make-error [operation reason cause]
  (ex-info "Secure random generation failed."
           {:type :portable/random-error :operation operation :reason reason} cause))

(defn- require-source! [operation]
  (let [provider (try (source/get-source!)
                      (catch #?(:clj Exception :cljs :default) error
                        (throw (make-error operation :entropy-failure error))))]
    (when-not provider
      (throw (ex-info "A secure random source is unavailable."
                      {:type :portable/unsupported-operation :operation operation :reason :secure-random-unavailable})))
    provider))

(defn- read-bytes! [provider amount operation]
  (try (source/fill-bytes! provider amount)
       (catch #?(:clj Exception :cljs :default) error
         (throw (make-error operation :entropy-failure error)))))

(defn generate-bytes!
  "Return an immutable unsigned byte vector from a secure platform source.
  Amount must be an integer from 0 through 1048576. Fail explicitly when secure entropy is unavailable."
  [amount]
  (let [operation :portable.random/generate-bytes!]
    (when-not (and (integer? amount) (<= 0 amount 1048576))
      (throw-invalid-input operation :invalid-byte-count))
    (read-bytes! (require-source! operation) amount operation)))

(defn generate-int!
  "Return a uniform integer from 0 inclusive to upper-bound exclusive.
  Upper-bound must be an integer from 1 through 9007199254740991."
  [upper-bound]
  (let [operation :portable.random/generate-int!]
    (when-not (and (integer? upper-bound) (<= 1 upper-bound 9007199254740991))
      (throw-invalid-input operation :invalid-upper-bound))
    (let [provider (require-source! operation)
          {:keys [byte-count mask]} (sampling/create-plan upper-bound)]
      (if (zero? byte-count)
        0
        (loop [attempt 0]
          (when (= attempt 128) (throw (make-error operation :rejection-limit nil)))
          (let [candidate (sampling/decode-sample (read-bytes! provider byte-count operation) mask)]
            (if (< candidate upper-bound) candidate (recur (inc attempt)))))))))
