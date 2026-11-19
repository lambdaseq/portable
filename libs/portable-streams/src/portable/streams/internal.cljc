(ns portable.streams.internal
  "Private stream adapter boundary, operation slots, and ownership."
  (:require [portable.bytes :as bytes]
            [portable.bytes.operation :as operation]))

(def ^:private STREAM_TAG #?(:clj (Object.) :cljs (js-obj)))

(defn invalid! [action argument reason]
  (throw (ex-info "Invalid stream input."
                  {:type :portable/invalid-input :operation action :argument argument :reason reason})))

(defn make-error [action reason cause]
  (ex-info "Stream operation failed." {:type :portable/stream-error :operation action :reason reason} cause))

(defn validate-callback [action callback]
  (when-not (fn? callback) (invalid! action :callback :expected-callback)))

(defn validate-options [action options allowed]
  (when-not (map? options) (invalid! action :options :expected-map))
  (when (some #(not (contains? allowed %)) (keys options)) (invalid! action :options :unknown-option)))

(defn require-stream [action endpoint direction]
  (when-not (and (map? endpoint) (identical? STREAM_TAG (::tag endpoint)))
    (invalid! action :stream :invalid-stream))
  (when (and direction (not= direction (:direction endpoint)))
    (invalid! action :stream :wrong-direction)))

(defn require-bytes [action data limit]
  (when-not (vector? data) (invalid! action :data :expected-byte-vector))
  (when (> (count data) limit) (invalid! action :data :input-too-large))
  (when-not (bytes/byte-vector? data) (invalid! action :data :expected-byte-vector)))

(defn create-endpoint! [direction perform! abort! resource]
  {::tag STREAM_TAG :direction direction :perform! perform! :abort! abort! :resource resource
   :state (atom {:closed? false :eof? false :finished? false :pending nil :owner nil})})

(defn bind-callback [callback]
  #?(:clj (bound-fn [error value] (callback error value)) :cljs callback))

(defn- deliver! [pending error result]
  (operation/dispatch! #((:callback pending) error result)))

(defn close! [endpoint]
  (let [[before _] (swap-vals! (:state endpoint) #(assoc % :closed? true :pending nil :owner nil))]
    (when-not (:closed? before)
      (try ((:abort! endpoint)) (catch #?(:clj Exception :cljs :default) _ nil))
      (when-let [pending (:pending before)]
        (deliver! pending (make-error (:operation pending) :closed nil) nil))))
  nil)

(defn- complete! [endpoint pending error value]
  (let [action (:action pending)
        invalid? (and (nil? error)
                      (case action
                        :read (not (or (nil? value)
                                       (and (bytes/byte-vector? value) (<= 1 (count value) (:max-bytes pending)))))
                        :write (not= (:expected-count pending) value)
                        :finish (some? value)))
        failure (cond error (make-error (:operation pending) (:reason (ex-data error) :io-error) error)
                      invalid? (make-error (:operation pending) :invalid-result nil))
        [before _] (swap-vals!
                    (:state endpoint)
                    (fn [state]
                      (if (identical? (:token pending) (get-in state [:pending :token]))
                        (cond-> (assoc state :pending nil)
                          failure (assoc :closed? true :owner nil)
                          (and (nil? failure) (= action :read) (nil? value)) (assoc :eof? true)
                          (and (nil? failure) (= action :finish)) (assoc :finished? true))
                        state)))]
    (when (identical? (:token pending) (get-in before [:pending :token]))
      (when failure (try ((:abort! endpoint)) (catch #?(:clj Exception :cljs :default) _ nil)))
      (deliver! pending failure (when-not failure value))))
  nil)

(defn start! [endpoint action options data callback owner]
  (let [pending (merge options {:action action :operation (keyword "portable.streams" (str (name action) "!"))
                                :callback (bind-callback callback) :expected-count (count data)
                                :token #?(:clj (Object.) :cljs (js-obj))})
        blocked-reason (fn [state]
                         (cond (:closed? state) :closed
                               (and (:owner state) (not (identical? owner (:owner state)))) :busy
                               (:pending state) :busy
                               (and (= action :write) (:finished? state)) :output-closed))
        [before after] (swap-vals! (:state endpoint)
                                   (fn [state]
                                     (if (or (blocked-reason state)
                                             (and (= action :read) (:eof? state))
                                             (and (= action :finish) (:finished? state)))
                                       state (assoc state :pending pending))))]
    (if (identical? (:token pending) (get-in after [:pending :token]))
      (operation/dispatch!
       #(when (identical? (:token pending) (get-in @(:state endpoint) [:pending :token]))
          (try ((:perform! endpoint) action options data (partial complete! endpoint pending))
               (catch #?(:clj Exception :cljs :default) error (complete! endpoint pending error nil)))))
      (let [reason (blocked-reason before)]
        (deliver! pending (when reason (make-error (:operation pending) reason nil)) nil))))
  nil)

(defn claim! [endpoint owner]
  (let [[before _] (swap-vals! (:state endpoint)
                               #(if (or (:closed? %) (:pending %) (:owner %)
                                        (and (= :sink (:direction endpoint)) (:finished? %)))
                                  % (assoc % :owner owner)))]
    (cond (:closed? before) :closed
          (or (:pending before) (:owner before)) :busy
          (and (= :sink (:direction endpoint)) (:finished? before)) :output-closed)))

(defn release! [endpoint owner]
  (swap! (:state endpoint) #(if (identical? owner (:owner %)) (assoc % :owner nil) %))
  nil)
