(ns portable.tls.jvm
  "Verified JSSE client sockets layered over connected portable TCP sockets."
  (:require [portable.tcp.internal :as transport]
            [portable.tls.internal :as internal])
  (:import [java.io ByteArrayInputStream]
           [java.net Socket]
           [java.nio.charset StandardCharsets]
           [java.security KeyStore]
           [java.security.cert CertificateFactory]
           [javax.net.ssl SSLContext SSLSession SSLSocket TrustManagerFactory]))

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

(defn upgrade! [connection options pending]
  (transport/dispatch!
   (fn []
     (let [result
           (try
             (let [context (create-context (:ca-certs options))
                   identity (:server-name options (:host options))
                   socket ^SSLSocket (.createSocket (.getSocketFactory context)
                                                    ^Socket (transport/get-native connection)
                                                    ^String identity (int (:port options)) true)
                   parameters (.getSSLParameters socket)]
               (internal/publish-native! connection socket)
               (.setUseClientMode socket true)
               (.setEndpointIdentificationAlgorithm parameters "HTTPS")
               (.setProtocols parameters (into-array String ["TLSv1.3" "TLSv1.2"]))
               (.setApplicationProtocols parameters (into-array String (:alpn options [])))
               (.setSSLParameters socket parameters)
               (.startHandshake socket)
               {:session {:version (.getProtocol ^SSLSession (.getSession socket))
                          :alpn (not-empty (.getApplicationProtocol socket))}})
             (catch Exception error {:error error}))]
       (if-let [error (:error result)]
         (transport/terminate! connection (internal/get-error-reason error) error :connect (:token pending))
         (transport/complete! connection :connect (:token pending) nil connection
                              {:tls-session (:session result)})))))
  nil)
