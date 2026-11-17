(ns portable.tls.internal
  "Private TLS ownership and handshake coordination over portable TCP."
  (:require [portable.tcp :as tcp]
            [portable.tcp.internal :as transport])
  #?(:clj (:import [java.net Socket])))

#?(:clj
   (defn- is-certificate-cause? [error]
     (some (fn [^Class type] (= "java.security.cert.CertificateException" (.getName type)))
           (take-while some? (iterate (fn [^Class type] (.getSuperclass type)) (class error))))))

(defn get-error-reason [error]
  (or (:reason (ex-data error))
      #?(:clj (if (some is-certificate-cause?
                       (take-while some? (iterate #(.getCause ^Throwable %) error)))
                :certificate-rejected
                :handshake-failed)
         :cljs (if (re-find #"CERT|SELF_SIGNED|UNABLE_TO_VERIFY|UNABLE_TO_GET_ISSUER" (or (.-code error) ""))
                 :certificate-rejected
                 :handshake-failed))))

(defn publish-native! [connection native]
  (reset! (:native-ref connection) native)
  (when (= :closed (:status @(:state connection)))
    #?(:clj (.close ^Socket native) :cljs (.destroy native))))

(defn- start-handshake! [connection options pending upgrade! error connected]
  (cond
    error (transport/terminate! connection (:reason (ex-data error)) error :connect (:token pending))
    (= :closed (:status @(:state connection))) (tcp/close! connected)
    :else
    (try
      (publish-native! connection (transport/get-native connected))
      (if (= :closed (:status @(:state connection)))
        (tcp/close! connected)
        (do (transport/release! connected) (upgrade! connection options pending)))
      (catch #?(:clj Exception :cljs :default) failure
        (transport/terminate! connection (get-error-reason failure) failure :connect (:token pending))))))

(defn connect! [options callback upgrade!]
  (let [resource (atom nil)
        raw (atom nil)
        abort! (fn []
                 (when-let [connection @raw] (tcp/close! connection))
                 (when-let [native @resource]
                   #?(:clj (.close ^Socket native) :cljs (.destroy native))))
        connection (assoc (transport/create-connection! nil abort! options callback
                                                        {:operation-ns "portable.tls" :error-type :portable/tls-error})
                          :native-ref resource)
        pending (transport/get-pending connection :connect)
        raw-connection
        (when pending
          (try
            (tcp/connect! (select-keys options [:host :port :timeout-ms])
                          (partial start-handshake! connection options pending upgrade!))
            (catch #?(:clj Exception :cljs :default) failure
              (transport/terminate! connection (transport/get-error-reason failure) failure :connect (:token pending))
              nil)))]
    (reset! raw raw-connection)
    (when (and raw-connection (= :closed (:status @(:state connection)))) (tcp/close! raw-connection))
    connection))
