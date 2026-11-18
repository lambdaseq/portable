(ns portable.tls.node
  "Verified Node TLS sockets layered over portable TCP sockets."
  (:require [clojure.string :as str]
            [portable.tcp.internal :as transport]
            [portable.tcp.node :as tcp]
            [portable.tls.internal :as internal]))

(def ^:private TLS
  (when (tcp/supported?) (js/require "node:tls")))

(def ^:private CRYPTO (when TLS (js/require "node:crypto")))
(def ^:private NET (when TLS (js/require "node:net")))

(defn supported? [] (some? TLS))

(defn- upgrade-client! [connection options pending]
  (when-let [certificates (:ca-certs options)]
    (try
      (doseq [pem certificates] (new (.-X509Certificate CRYPTO) pem))
      (catch :default error (throw (ex-info "Invalid TLS CA certificate." {:reason :invalid-ca} error)))))
  (let [identity (:server-name options (:host options))
        native-options #js {:socket (transport/get-native connection) :host identity
                            :rejectUnauthorized true :minVersion "TLSv1.2" :maxVersion "TLSv1.3"
                            :allowHalfOpen true :highWaterMark 65536
                            :ALPNProtocols (to-array (:alpn options []))}]
    (when (and (zero? (.isIP NET identity)) (str/includes? identity ".")
               (not (str/ends-with? identity ".")))
      (set! (.-servername native-options) identity))
    (when-let [certificates (:ca-certs options)]
      (set! (.-ca native-options) (to-array certificates)))
    (let [socket (.connect TLS native-options)]
      (internal/publish-native! connection socket)
      (tcp/attach-events! connection socket
                          #(if (= :connecting (:status @(:state connection)))
                             (internal/get-error-reason %)
                             (transport/get-error-reason %)))
      (.once socket "secureConnect"
             (fn []
               (if-not (.-authorized socket)
                 (transport/terminate! connection :certificate-rejected nil :connect (:token pending))
                 (transport/complete! connection :connect (:token pending) nil connection
                                      {:tls-session {:version (.getProtocol socket)
                                                     :alpn (or (.-alpnProtocol socket) nil)}}))))))
  nil)

(defn- upgrade-server! [connection options pending]
  (let [context (try
                  (let [certificates (:cert-chain options)]
                    (doseq [pem certificates] (new (.-X509Certificate CRYPTO) pem))
                    (let [key (.createPrivateKey CRYPTO (:private-key options))]
                      (when-not (#{"rsa" "ec"} (.-asymmetricKeyType key))
                        (throw (js/Error. "Unsupported server key algorithm."))))
                    (.createSecureContext TLS #js {:cert (str/join "\n" certificates) :key (:private-key options)
                                                   :minVersion "TLSv1.2" :maxVersion "TLSv1.3"}))
                  (catch :default error (throw (ex-info "Invalid TLS server identity." {:reason :invalid-identity} error))))
        socket (new (.-TLSSocket TLS) (transport/get-native connection)
                    #js {:isServer true :secureContext context :requestCert false :allowHalfOpen true
                         :highWaterMark 65536 :ALPNProtocols (to-array (:alpn options []))})]
    (internal/publish-native! connection socket)
    (tcp/attach-events! connection socket
                        #(if (= :connecting (:status @(:state connection)))
                           (internal/get-error-reason %) (transport/get-error-reason %)))
    (.once socket "secure"
           #(transport/complete! connection :connect (:token pending) nil connection
                                 {:tls-session {:version (.getProtocol socket)
                                                :alpn (or (.-alpnProtocol socket) nil)}})))
  nil)

(defn upgrade! [connection options pending]
  (if (:server? options)
    (upgrade-server! connection options pending)
    (upgrade-client! connection options pending)))
