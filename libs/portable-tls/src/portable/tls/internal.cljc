(ns portable.tls.internal
  "Private TLS ownership and handshake coordination over portable TCP."
  (:require [clojure.string :as str]
            [portable.tcp :as tcp]
            [portable.tcp.internal :as transport])
  #?(:clj (:import [java.net Socket])))

(defn- certificate-pem? [pem]
  (and (string? pem)
       (re-matches #"[ \r\n\t]*-----BEGIN CERTIFICATE-----[ \r\n\t]+[A-Za-z0-9+/= \r\n\t]+-----END CERTIFICATE-----[ \r\n\t]*" pem)))

(defn validate-ca-certs [operation certificates]
  (when-not (and (vector? certificates) (seq certificates) (every? certificate-pem? certificates))
    (transport/throw-invalid-input operation :invalid-ca-certs)))

(defn validate-server-identity [options]
  (let [chain (:cert-chain options) key (:private-key options)]
    (when-not (and (vector? chain) (<= 1 (count chain) 16)
                   (every? #(and (string? %) (<= (count %) 65536) (certificate-pem? %)) chain))
      (transport/throw-invalid-input :portable.tls/accept! :invalid-cert-chain))
    (when-not (and (string? key) (<= (count key) 65536)
                   (re-matches #"[ \r\n\t]*-----BEGIN PRIVATE KEY-----[ \r\n\t]+[A-Za-z0-9+/= \r\n\t]+-----END PRIVATE KEY-----[ \r\n\t]*" key))
      (transport/throw-invalid-input :portable.tls/accept! :invalid-private-key))))

(defn validate-alpn [operation options]
  (when (and (contains? options :alpn)
             (not (and (vector? (:alpn options))
                       (every? #(and (string? %) (<= 1 (count %) 255) (re-matches #"[!-~]+" %)) (:alpn options)))))
    (transport/throw-invalid-input operation :invalid-alpn)))

(defn decode-private-key [pem]
  (-> pem (str/replace "-----BEGIN PRIVATE KEY-----" "")
      (str/replace "-----END PRIVATE KEY-----" "") (str/replace #"[ \r\n\t]" "")))

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

(defn accept! [raw options callback upgrade!]
  (let [native (try (transport/release! raw)
                    (catch #?(:clj Exception :cljs :default) error
                      (throw (ex-info "TCP connection cannot transfer to server TLS."
                                      {:type :portable/tls-error :operation :portable.tls/accept!
                                       :reason (:reason (ex-data error) :busy)} error))))
        resource (atom native)
        abort! (fn [] (when-let [socket @resource]
                       #?(:clj (.close ^Socket socket) :cljs (.destroy socket))))
        connection (assoc (transport/create-connection! nil abort! options callback
                                                        {:operation-ns "portable.tls" :error-type :portable/tls-error
                                                         :initial-operation :accept!})
                          :native-ref resource)]
    (transport/dispatch!
     (fn []
       (when-let [pending (transport/get-pending connection :connect)]
         (try (upgrade! connection (assoc options :server? true) pending)
              (catch #?(:clj Exception :cljs :default) error
                (transport/terminate! connection (get-error-reason error) error :connect (:token pending)))))))
    connection))
