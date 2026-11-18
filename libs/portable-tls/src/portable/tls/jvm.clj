(ns portable.tls.jvm
  "JSSE client and server sockets layered over connected portable TCP sockets."
  (:require [portable.tcp.internal :as transport]
            [portable.tls.internal :as internal])
  (:import [java.io ByteArrayInputStream]
           [java.net InetAddress Socket]
           [java.nio.charset StandardCharsets]
           [java.security KeyFactory KeyStore]
           [java.security.spec PKCS8EncodedKeySpec]
           [java.util Base64]
           [java.security.cert CertificateFactory X509Certificate]
           [javax.net.ssl KeyManagerFactory SSLContext SSLSession SSLSocket TrustManagerFactory]))

(defn- create-trust-store [certificates]
  (when certificates
    (try
      (let [store (KeyStore/getInstance (KeyStore/getDefaultType))
            factory (CertificateFactory/getInstance "X.509")]
        (.load store nil nil)
        (doseq [[index pem] (map-indexed vector certificates)]
          (with-open [input (ByteArrayInputStream. (.getBytes ^String pem StandardCharsets/UTF_8))]
            (.setCertificateEntry store (str "portable-ca-" index) (.generateCertificate factory input))))
        store)
      (catch Exception error
        (throw (ex-info "Invalid TLS CA certificate." {:reason :invalid-ca} error))))))

(defn- create-context [certificates]
  (let [trust (TrustManagerFactory/getInstance (TrustManagerFactory/getDefaultAlgorithm))
        context (SSLContext/getInstance "TLS")]
    (.init trust ^KeyStore (create-trust-store certificates))
    (.init context nil (.getTrustManagers trust) nil)
    context))

(defn- create-server-context [options]
  (try
    (let [factory (CertificateFactory/getInstance "X.509")
          chain (mapv (fn [^String pem]
                        (with-open [input (ByteArrayInputStream. (.getBytes pem StandardCharsets/UTF_8))]
                          (.generateCertificate factory input))) (:cert-chain options))
          algorithm (.getAlgorithm (.getPublicKey ^X509Certificate (first chain)))
          _ (when-not (#{"RSA" "EC"} algorithm) (throw (ex-info "Unsupported server key algorithm." {})))
          encoded (.decode (Base64/getDecoder) ^String (internal/decode-private-key (:private-key options)))
          key (.generatePrivate (KeyFactory/getInstance algorithm) (PKCS8EncodedKeySpec. encoded))
          store (KeyStore/getInstance "PKCS12")
          password (char-array 0)
          manager (KeyManagerFactory/getInstance (KeyManagerFactory/getDefaultAlgorithm))
          context (SSLContext/getInstance "TLS")]
      (.load store nil nil)
      (.setKeyEntry store "portable-server" key password (into-array X509Certificate chain))
      (.init manager store password)
      (.init context (.getKeyManagers manager) nil nil)
      context)
    (catch Exception error (throw (ex-info "Invalid TLS server identity." {:reason :invalid-identity} error)))))

(defn upgrade! [connection options pending]
  (transport/dispatch!
   (fn []
     (let [result
           (try
             (let [server? (:server? options)
                   context (if server? (create-server-context options) (create-context (:ca-certs options)))
                   raw ^Socket (transport/get-native connection)
                   identity (if server? (.getHostAddress ^InetAddress (.getInetAddress raw))
                                (:server-name options (:host options)))
                   port (if server? (.getPort raw) (:port options))
                   socket ^SSLSocket (.createSocket (.getSocketFactory context) raw ^String identity (int port) true)
                   parameters (.getSSLParameters socket)]
               (internal/publish-native! connection socket)
               (.setUseClientMode socket (not server?))
               (if server? (.setNeedClientAuth socket false)
                   (.setEndpointIdentificationAlgorithm parameters "HTTPS"))
               (.setProtocols parameters (into-array String ["TLSv1.3" "TLSv1.2"]))
               (.setApplicationProtocols parameters (into-array String (:alpn options [])))
               (.setSSLParameters socket parameters)
               (.startHandshake socket)
               {:session {:version (.getProtocol ^SSLSession (.getSession socket))
                          :alpn (not-empty (.getApplicationProtocol socket))}})
             (catch Exception error {:error error}))]
       (if-let [error (:error result)]
         (transport/terminate! connection (internal/get-error-reason error) error :connect (:token pending))
         (transport/complete! connection :connect (:token pending) nil connection {:tls-session (:session result)})))))
  nil)
