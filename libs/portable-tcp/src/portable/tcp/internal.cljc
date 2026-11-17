(ns portable.tcp.internal
  "Private connection state and callback lifecycle shared by transport adapters."
  #?(:clj (:import [java.util.concurrent ScheduledThreadPoolExecutor ThreadFactory TimeUnit])))

(def ^:private CONNECTION_TAG #?(:clj (Object.) :cljs (js-obj)))

#?(:clj
   (def ^:private TIMER
     (doto (ScheduledThreadPoolExecutor.
            1 (reify ThreadFactory
                (newThread [_ runnable]
                  (doto (Thread. runnable "portable-tcp-timer") (.setDaemon true)))))
       (.setRemoveOnCancelPolicy true))))

(defn dispatch! [f]
  #?(:clj (doto (Thread. ^Runnable (bound-fn [] (f)) "portable-tcp-worker")
            (.setDaemon true)
            (.start))
     :cljs (js/queueMicrotask f)))

(defn make-error [connection operation reason cause]
  (ex-info "Transport operation failed."
           {:type (:error-type connection :portable/tcp-error) :operation operation :reason reason} cause))

(defn get-operation [connection action]
  (keyword (:operation-ns connection "portable.tcp") (name action)))

(defn get-native [connection]
  (if-let [resource (:native-ref connection)] @resource (:native connection)))

(defn throw-invalid-input [operation reason]
  (throw (ex-info "Invalid transport input." {:type :portable/invalid-input :operation operation :reason reason})))

(defn validate-callback [operation callback]
  (when-not (fn? callback) (throw-invalid-input operation :expected-callback)))

(defn validate-options [operation options allowed]
  (when-not (map? options) (throw-invalid-input operation :expected-options-map))
  (when (some #(not (contains? allowed %)) (keys options))
    (throw-invalid-input operation :unknown-option)))

(defn parse-timeout [operation options]
  (let [timeout (:timeout-ms options 10000)]
    (when-not (and (integer? timeout) (<= 1 timeout 2147483647))
      (throw-invalid-input operation :invalid-timeout))
    (assoc options :timeout-ms timeout)))

(defn require-connection [operation connection]
  (when-not (and (map? connection) (identical? CONNECTION_TAG (::tag connection))
                 (= (namespace operation) (:operation-ns connection "portable.tcp")))
    (throw-invalid-input operation :invalid-connection)))

(defn get-pending [connection slot]
  (get @(:state connection) slot))

(defn- cancel-timer! [pending]
  (when-let [timer @(:timer pending)]
    #?(:clj (.cancel ^java.util.concurrent.Future timer false)
       :cljs (js/clearTimeout timer))))

(defn- deliver! [pending error value]
  (cancel-timer! pending)
  (dispatch! #((:callback pending) error value)))

(defn terminate!
  ([connection reason cause] (terminate! connection reason cause nil nil))
  ([connection reason cause slot token]
   (let [[before after]
         (swap-vals! (:state connection)
                     (fn [state]
                       (if (and (not= :closed (:status state))
                                (or (nil? slot) (identical? token (:token (slot state)))))
                         (assoc state :status :closed :connect nil :read nil :write nil)
                         state)))]
     (when (and (not= :closed (:status before)) (= :closed (:status after)))
       ;; Closing is best effort after failure; pending callbacks must still settle.
       (try ((:abort! connection)) (catch #?(:clj Exception :cljs :default) _ nil))
       (doseq [pending (keep before [:connect :read :write])]
         (deliver! pending (make-error connection (:operation pending) reason cause) nil))))
   nil))

(defn- arm-timeout! [connection slot pending timeout-ms]
  (let [expire! #(terminate! connection :timeout nil slot (:token pending))
        timer #?(:clj (.schedule TIMER ^Runnable (bound-fn [] (expire!))
                                 (long timeout-ms) TimeUnit/MILLISECONDS)
                 :cljs (js/setTimeout expire! timeout-ms))]
    (reset! (:timer pending) timer)
    (when-not (identical? (:token pending) (:token (get-pending connection slot)))
      (cancel-timer! pending))))

(defn- make-pending [operation options callback]
  (merge options {:operation operation :callback callback
                  :token #?(:clj (Object.) :cljs (js-obj)) :timer (atom nil)}))

(defn create-connection!
  ([native abort! options callback] (create-connection! native abort! options callback {}))
  ([native abort! options callback configuration]
   (let [pending (make-pending (get-operation configuration :connect!) options callback)
         connection (merge configuration
                           {::tag CONNECTION_TAG :native native :abort! abort!
                            :state (atom {:status :connecting :connect pending :read nil :write nil
                                          :eof? false :output-closed? false})})]
     (arm-timeout! connection :connect pending (:timeout-ms options))
     connection)))

(defn release! [connection]
  (require-connection :portable.tcp/release! connection)
  (let [[before _] (swap-vals! (:state connection)
                               (fn [state]
                                 (if (and (= :open (:status state)) (nil? (:read state)) (nil? (:write state)))
                                   (assoc state :status :closed)
                                   state)))]
    (when-not (and (= :open (:status before)) (nil? (:read before)) (nil? (:write before)))
      (throw (make-error connection :portable.tcp/release! :busy nil)))
    (when-let [detach! (:detach! connection)] (detach!))
    (get-native connection)))

(defn complete!
  ([connection slot token error value] (complete! connection slot token error value {}))
  ([connection slot token error value updates]
   (let [[before _]
        (swap-vals! (:state connection)
                    (fn [state]
                      (if (identical? token (:token (slot state)))
                        (cond-> (assoc (merge state updates) slot nil)
                          (and (= :connect slot) (nil? error)) (assoc :status :open))
                        state)))
        pending (slot before)]
    (when (and pending (identical? token (:token pending)))
      (deliver! pending error value)))
   nil))

(defn reserve! [connection slot operation options callback]
  (let [pending (make-pending operation options callback)
        [before after]
        (swap-vals! (:state connection)
                    (fn [state]
                      (if (and (= :open (:status state)) (nil? (slot state))
                               (not (and (= :read slot) (:eof? state)))
                               (not (and (= :write slot) (:output-closed? state))))
                        (cond-> (assoc state slot pending)
                          (= (get-operation connection :shutdown-output!) operation)
                          (assoc :output-closed? true))
                        state)))]
    (if (identical? (:token pending) (:token (slot after)))
      (do (arm-timeout! connection slot pending (:timeout-ms options)) pending)
      (let [reason (cond
                     (= :closed (:status before)) :closed
                     (= :connecting (:status before)) :not-connected
                     (slot before) :busy
                     (and (= :write slot) (:output-closed? before)) :output-closed
                     :else nil)]
        (deliver! pending (when reason (make-error connection operation reason nil)) nil)
        nil))))

(defn get-error-reason [error]
  #?(:clj (case (.getName (class error))
            "java.net.ConnectException" :connection-refused
            "java.net.UnknownHostException" :dns-failure
            "java.net.SocketTimeoutException" :timeout
            :io-error)
     :cljs (case (.-code error)
             "ECONNREFUSED" :connection-refused
             ("ENOTFOUND" "EAI_AGAIN") :dns-failure
             "ECONNRESET" :connection-reset
             "ETIMEDOUT" :timeout
             :io-error)))
