(ns portable.tls
  "Verified asynchronous TLS clients over portable TCP. Browsers are unsupported."
  (:require [clojure.string :as str]
            [portable.bytes :as bytes]
            [portable.tcp.internal :as transport]
            [portable.tls.internal :as internal]
            #?(:clj [portable.tcp.jvm :as io] :cljs [portable.tcp.node :as io])
            #?(:clj [portable.tls.jvm :as backend] :cljs [portable.tls.node :as backend])))

(defn- require-supported [operation]
  (when-not #?(:clj true :cljs (backend/supported?) :default false)
    (throw (ex-info "TLS sockets are unsupported on this runtime."
                    {:type :portable/unsupported-operation :operation operation}))))

(defn- is-valid-name? [value]
  (and (string? value) (not (str/blank? value)) (not (str/includes? value "\u0000"))))

(defn- is-server-name? [value]
  (and (string? value) (<= (count value) 253) (not (re-matches #"[0-9.]+" value))
       (some? (re-matches #"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+" value))))

(defn- is-pem-certificate? [value]
  (and (string? value)
       (some? (re-matches #"[ \r\n\t]*-----BEGIN CERTIFICATE-----[ \r\n\t]+[A-Za-z0-9+/= \r\n\t]+-----END CERTIFICATE-----[ \r\n\t]*" value))))

(defn connect!
  "Connect over TCP and complete a verified TLS handshake. Return a cancellable opaque handle.
  Options: :host, :port, :timeout-ms, :server-name, :ca-certs, :alpn. Callback: (error connection)."
  [options callback]
  (transport/validate-options :portable.tls/connect! options #{:host :port :timeout-ms :server-name :ca-certs :alpn})
  (transport/validate-callback :portable.tls/connect! callback)
  (let [{:keys [host port] :as parsed} (transport/parse-timeout :portable.tls/connect! options)]
    (when-not (is-valid-name? host) (transport/throw-invalid-input :portable.tls/connect! :invalid-host))
    (when-not (and (integer? port) (<= 1 port 65535))
      (transport/throw-invalid-input :portable.tls/connect! :invalid-port))
    (when (and (contains? parsed :server-name) (not (is-server-name? (:server-name parsed))))
      (transport/throw-invalid-input :portable.tls/connect! :invalid-server-name))
    (when (and (contains? parsed :ca-certs)
               (not (and (vector? (:ca-certs parsed)) (seq (:ca-certs parsed))
                         (every? is-pem-certificate? (:ca-certs parsed)))))
      (transport/throw-invalid-input :portable.tls/connect! :invalid-ca-certs))
    (when (and (contains? parsed :alpn)
               (not (and (vector? (:alpn parsed))
                         (every? #(and (string? %) (<= 1 (count %) 255) (re-matches #"[!-~]+" %)) (:alpn parsed)))))
      (transport/throw-invalid-input :portable.tls/connect! :invalid-alpn))
    (require-supported :portable.tls/connect!)
    (internal/connect! parsed callback backend/upgrade!)))

(defn read!
  "Read a bounded plaintext byte vector, or nil at EOF. Uses TCP read options and callback contract."
  ([connection callback] (read! connection {} callback))
  ([connection options callback]
   (transport/validate-options :portable.tls/read! options #{:max-bytes :timeout-ms})
   (transport/validate-callback :portable.tls/read! callback)
   (let [parsed (assoc (transport/parse-timeout :portable.tls/read! options) :max-bytes (:max-bytes options 65536))
         size (:max-bytes parsed)]
     (when-not (and (integer? size) (<= 1 size 1048576))
       (transport/throw-invalid-input :portable.tls/read! :invalid-read-size))
     (require-supported :portable.tls/read!)
     (transport/require-connection :portable.tls/read! connection)
     (io/read! connection parsed callback))))

(defn write!
  "Write plaintext unsigned bytes through TLS. Uses TCP byte limits and callback contract."
  ([connection data callback] (write! connection data {} callback))
  ([connection data options callback]
   (transport/validate-options :portable.tls/write! options #{:timeout-ms})
   (transport/validate-callback :portable.tls/write! callback)
   (when-not (bytes/byte-vector? data) (transport/throw-invalid-input :portable.tls/write! :expected-byte-vector))
   (when (> (count data) 1048576) (transport/throw-invalid-input :portable.tls/write! :write-too-large))
   (let [parsed (transport/parse-timeout :portable.tls/write! options)]
     (require-supported :portable.tls/write!)
     (transport/require-connection :portable.tls/write! connection)
     (io/write! connection data parsed callback))))

(defn shutdown-output!
  "Send TLS close_notify and close output, keeping input available where the native TLS version allows."
  ([connection callback] (shutdown-output! connection {} callback))
  ([connection options callback]
   (transport/validate-options :portable.tls/shutdown-output! options #{:timeout-ms})
   (transport/validate-callback :portable.tls/shutdown-output! callback)
   (let [parsed (transport/parse-timeout :portable.tls/shutdown-output! options)]
     (require-supported :portable.tls/shutdown-output!)
     (transport/require-connection :portable.tls/shutdown-output! connection)
     (io/shutdown-output! connection parsed callback))))

(defn close!
  "Abort TLS and its underlying TCP connection, settling pending callbacks. Safe to repeat. Return nil."
  [connection]
  (require-supported :portable.tls/close!)
  (transport/require-connection :portable.tls/close! connection)
  (transport/terminate! connection :closed nil))

(defn get-session-info
  "Return {:version string :alpn string-or-nil} after a successful handshake, including after close."
  [connection]
  (require-supported :portable.tls/get-session-info)
  (transport/require-connection :portable.tls/get-session-info connection)
  (or (:tls-session @(:state connection))
      (throw (transport/make-error connection :portable.tls/get-session-info :not-connected nil))))
