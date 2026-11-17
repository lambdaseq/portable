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

(defn upgrade! [connection options pending]
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
