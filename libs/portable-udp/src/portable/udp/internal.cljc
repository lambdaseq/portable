(ns portable.udp.internal
  "Private datagram state, input validation, and bounded callback lifecycle."
  (:require [clojure.string :as str])
  #?(:clj (:import [java.util.concurrent ScheduledThreadPoolExecutor ThreadFactory TimeUnit])))

(def MAX_PAYLOAD 65507)
(def ^:private SOCKET_TAG #?(:clj (Object.) :cljs (js-obj)))
(def ^:private SLOTS [:bind :send :receive])

#?(:clj
   (def ^:private TIMER
     (doto (ScheduledThreadPoolExecutor.
            1 (reify ThreadFactory
                (newThread [_ runnable]
                  (doto (Thread. runnable "portable-udp-timer") (.setDaemon true)))))
       (.setRemoveOnCancelPolicy true))))

(defn dispatch! [f]
  #?(:clj (doto (Thread. ^Runnable (bound-fn [] (f)) "portable-udp-worker") (.setDaemon true) (.start))
     :cljs (js/queueMicrotask f)))

(defn throw-invalid-input [operation reason]
  (throw (ex-info "Invalid UDP input." {:type :portable/invalid-input :operation operation :reason reason})))

(defn validate-options [operation options allowed callback]
  (when-not (map? options) (throw-invalid-input operation :expected-options-map))
  (when (some #(not (contains? allowed %)) (keys options)) (throw-invalid-input operation :unknown-option))
  (when-not (fn? callback) (throw-invalid-input operation :expected-callback))
  (let [timeout (:timeout-ms options 10000)]
    (when-not (and (integer? timeout) (<= 1 timeout 2147483647))
      (throw-invalid-input operation :invalid-timeout))
    (assoc options :timeout-ms timeout)))

(defn parse-address [value]
  (when (and (string? value) (re-matches #"(?:0|[1-9][0-9]{0,2})(?:\.(?:0|[1-9][0-9]{0,2})){3}" value))
    (let [octets (mapv (fn [part]
                         (reduce (fn [number character]
                                   (+ (* number 10) (- #?(:clj (int character) :cljs (.charCodeAt character 0)) 48)))
                                 0 part))
                       (str/split value #"\."))]
      (when (every? #(<= % 255) octets) octets))))

(defn validate-address [operation address binding?]
  (let [octets (parse-address address)]
    (when-not (and octets (or (and binding? (= [0 0 0 0] octets)) (< 0 (first octets) 224)))
      (throw-invalid-input operation :invalid-address))))

(defn validate-port [operation port minimum]
  (when-not (and (integer? port) (<= minimum port 65535)) (throw-invalid-input operation :invalid-port)))

(defn require-socket [operation socket]
  (when-not (and (map? socket) (identical? SOCKET_TAG (::tag socket)))
    (throw-invalid-input operation :invalid-socket)))

(defn make-error [operation reason cause]
  (ex-info "UDP operation failed." {:type :portable/udp-error :operation operation :reason reason} cause))

(defn get-pending [socket slot] (get @(:state socket) slot))

(defn- settle! [pending error value]
  (when-let [timer @(:timer pending)]
    #?(:clj (.cancel ^java.util.concurrent.Future timer false) :cljs (js/clearTimeout timer)))
  (dispatch! #((:callback pending) error value)))

(defn- close-native! [socket]
  (when-let [native @(:native socket)]
    (try ((:close-native! socket) native) (catch #?(:clj Exception :cljs :default) _ nil))))

(defn terminate!
  ([socket reason cause] (terminate! socket reason cause nil nil))
  ([socket reason cause slot token]
   (let [[before after] (swap-vals! (:state socket)
                                  (fn [state]
                                    (if (and (not= :closed (:status state))
                                             (or (nil? slot) (identical? token (:token (get state slot)))))
                                      (assoc state :status :closed :bind nil :send nil :receive nil :queue [] :queued-bytes 0)
                                      state)))]
     (when (and (not= :closed (:status before)) (= :closed (:status after)))
       (close-native! socket)
       (doseq [pending (keep before SLOTS)]
         (settle! pending (make-error (:operation pending) reason cause) nil))))
   nil))

(defn- arm-timeout! [socket slot pending timeout]
  (let [expire! #(terminate! socket :timeout nil slot (:token pending))
        timer #?(:clj (.schedule TIMER ^Runnable (bound-fn [] (expire!)) (long timeout) TimeUnit/MILLISECONDS)
                 :cljs (js/setTimeout expire! timeout))]
    (reset! (:timer pending) timer)
    (when-not (identical? (:token pending) (:token (get-pending socket slot)))
      #?(:clj (.cancel ^java.util.concurrent.Future timer false) :cljs (js/clearTimeout timer)))))

(defn- create-pending [slot options callback]
  (merge options {:operation (keyword "portable.udp" (str (name slot) "!")) :callback callback
                  :token #?(:clj (Object.) :cljs (js-obj)) :timer (atom nil)}))

(defn create-socket! [close-native! options callback]
  (let [pending (create-pending :bind options callback)
        socket {::tag SOCKET_TAG :native (atom nil) :close-native! close-native!
                :state (atom {:status :binding :bind pending :send nil :receive nil :queue [] :queued-bytes 0})}]
    (arm-timeout! socket :bind pending (:timeout-ms options))
    socket))

(defn set-native! [socket native]
  (reset! (:native socket) native)
  (when (= :closed (:status @(:state socket))) (close-native! socket)))

(defn complete! [socket slot token error value]
  (let [[before _] (swap-vals! (:state socket)
                              (fn [state]
                                (if (identical? token (:token (get state slot)))
                                  (cond-> (assoc state slot nil)
                                    (and (= slot :bind) (nil? error)) (assoc :status :bound))
                                  state)))
        pending (get before slot)]
    (when (and pending (identical? token (:token pending)))
      (if (and (= :receive slot) (nil? error) (> (count (:data value)) (:max-bytes pending)))
        (settle! pending (make-error (:operation pending) :datagram-too-large nil) nil)
        (settle! pending error value))))
  nil)

(defn reserve! [socket slot options callback]
  (let [pending (create-pending slot options callback)
        [before after] (swap-vals! (:state socket)
                                  (fn [state]
                                    (if (and (= :bound (:status state)) (nil? (get state slot)))
                                      (assoc state slot pending) state)))]
    (if (identical? (:token pending) (:token (get after slot)))
      (do (arm-timeout! socket slot pending (:timeout-ms options)) pending)
      (do (settle! pending (make-error (:operation pending)
                                      (case (:status before) :closed :closed :binding :not-bound :busy) nil) nil)
          nil))))

(defn enqueue! [socket datagram]
  (let [size (count (:data datagram))]
    (swap! (:state socket)
           (fn [state]
             (if (and (= :bound (:status state)) (< (count (:queue state)) 64)
                      (<= (+ (:queued-bytes state) size) 1048576))
               (-> state (update :queue conj datagram) (update :queued-bytes + size)) state))))
  nil)

(defn take-datagram! [socket]
  (let [[before _] (swap-vals! (:state socket)
                              (fn [state]
                                (if (and (:receive state) (seq (:queue state)))
                                  (-> state (update :queued-bytes - (count (:data (first (:queue state)))))
                                      (update :queue #(into [] (subvec % 1)))) state)))]
    (when (:receive before) (first (:queue before)))))

(defn get-error-reason [error]
  #?(:clj (case (.getName (class error)) "java.net.BindException" :bind-failed :io-error)
     :cljs (case (.-code error) ("EADDRINUSE" "EADDRNOTAVAIL") :bind-failed :io-error)))
