(ns portable.tcp
  "Asynchronous TCP listeners and connections with error-first callbacks and bounded byte chunks."
  (:require [clojure.string :as str]
            [portable.bytes :as bytes]
            [portable.tcp.internal :as internal]
            #?(:clj [portable.tcp.jvm :as backend]
               :cljs [portable.tcp.node :as backend])))

(defn- require-supported [operation]
  (when-not #?(:clj true :cljs (backend/supported?) :default false)
    (throw (ex-info "TCP sockets are unsupported on this runtime."
                    {:type :portable/unsupported-operation :operation operation}))))

(defn connect!
  "Connect asynchronously. Return an opaque handle immediately; callback receives (error connection).
  Options: :host, :port, and :timeout-ms (default 10000). Close the handle to cancel."
  [options callback]
  (internal/validate-options :portable.tcp/connect! options #{:host :port :timeout-ms})
  (internal/validate-callback :portable.tcp/connect! callback)
  (let [{:keys [host port] :as parsed} (internal/parse-timeout :portable.tcp/connect! options)]
    (when-not (and (string? host) (not (str/blank? host)) (not (str/includes? host "\u0000")))
      (internal/throw-invalid-input :portable.tcp/connect! :invalid-host))
    (when-not (and (integer? port) (<= 1 port 65535))
      (internal/throw-invalid-input :portable.tcp/connect! :invalid-port))
    (require-supported :portable.tcp/connect!)
    (backend/connect! parsed callback)))

(defn- ipv4-host? [host]
  (and (string? host)
       (let [octets (str/split host #"\." -1)]
         (and (= 4 (count octets))
              (every? #(re-matches #"(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])" %) octets)))))

(defn listen!
  "Bind a numeric IPv4 address. Return a listener immediately; callback receives (error listener).
  Defaults: :host 127.0.0.1, :port 0 (ephemeral), :backlog 128, :timeout-ms 10000."
  [options callback]
  (internal/validate-options :portable.tcp/listen! options #{:host :port :backlog :timeout-ms})
  (internal/validate-callback :portable.tcp/listen! callback)
  (let [parsed (merge {:host "127.0.0.1" :port 0 :backlog 128}
                      (internal/parse-timeout :portable.tcp/listen! options))
        {:keys [host port backlog]} parsed]
    (when-not (ipv4-host? host)
      (internal/throw-invalid-input :portable.tcp/listen! :invalid-host))
    (when-not (and (integer? port) (<= 0 port 65535))
      (internal/throw-invalid-input :portable.tcp/listen! :invalid-port))
    (when-not (and (integer? backlog) (<= 1 backlog 1024))
      (internal/throw-invalid-input :portable.tcp/listen! :invalid-backlog))
    (require-supported :portable.tcp/listen!)
    (backend/listen! parsed callback)))

(defn accept!
  "Accept one connection asynchronously. Callback: (error connection). Return nil.
  One accept may be pending. A timeout closes the listener, leaving delivered connections open."
  ([listener callback] (accept! listener {} callback))
  ([listener options callback]
   (internal/validate-options :portable.tcp/accept! options #{:timeout-ms})
   (internal/validate-callback :portable.tcp/accept! callback)
   (let [parsed (internal/parse-timeout :portable.tcp/accept! options)]
     (require-supported :portable.tcp/accept!)
     (internal/require-listener :portable.tcp/accept! listener)
     (backend/accept! listener parsed callback))))

(defn local-address
  "Return the listener's bound {:host numeric-ipv4 :port integer} after successful listen."
  [listener]
  (require-supported :portable.tcp/local-address)
  (internal/require-listener :portable.tcp/local-address listener)
  (or @(:bound-address listener)
      (throw (internal/make-error listener :portable.tcp/local-address :not-listening nil))))

(defn read!
  "Read up to :max-bytes (default 65536, maximum 1048576). Callback receives (error bytes-or-nil).
  Nil means EOF. One read may be pending. A timeout closes the connection."
  ([connection callback] (read! connection {} callback))
  ([connection options callback]
   (internal/validate-options :portable.tcp/read! options #{:max-bytes :timeout-ms})
   (internal/validate-callback :portable.tcp/read! callback)
   (let [parsed (assoc (internal/parse-timeout :portable.tcp/read! options) :max-bytes (:max-bytes options 65536))
         limit (:max-bytes parsed)]
     (when-not (and (integer? limit) (<= 1 limit 1048576))
       (internal/throw-invalid-input :portable.tcp/read! :invalid-read-size))
     (require-supported :portable.tcp/read!)
     (internal/require-connection :portable.tcp/read! connection)
     (backend/read! connection parsed callback))))

(defn write!
  "Write an unsigned byte vector of at most 1048576 bytes. Callback receives (error byte-count).
  One write may be pending. Completion means local acceptance, not peer receipt."
  ([connection data callback] (write! connection data {} callback))
  ([connection data options callback]
   (internal/validate-options :portable.tcp/write! options #{:timeout-ms})
   (internal/validate-callback :portable.tcp/write! callback)
   (when-not (bytes/byte-vector? data)
     (internal/throw-invalid-input :portable.tcp/write! :expected-byte-vector))
   (when (> (count data) 1048576) (internal/throw-invalid-input :portable.tcp/write! :write-too-large))
   (let [parsed (internal/parse-timeout :portable.tcp/write! options)]
     (require-supported :portable.tcp/write!)
     (internal/require-connection :portable.tcp/write! connection)
     (backend/write! connection data parsed callback))))

(defn shutdown-output!
  "Send EOF after completed writes, keeping input open. Callback receives (error nil).
  Uses the write slot; wait for prior writes before calling."
  ([connection callback] (shutdown-output! connection {} callback))
  ([connection options callback]
   (internal/validate-options :portable.tcp/shutdown-output! options #{:timeout-ms})
   (internal/validate-callback :portable.tcp/shutdown-output! callback)
   (let [parsed (internal/parse-timeout :portable.tcp/shutdown-output! options)]
     (require-supported :portable.tcp/shutdown-output!)
     (internal/require-connection :portable.tcp/shutdown-output! connection)
     (backend/shutdown-output! connection parsed callback))))

(defn close!
  "Abort a connection or listener and settle pending callbacks with :closed errors. Safe to repeat. Return nil."
  [connection]
  (require-supported :portable.tcp/close!)
  (when-not (internal/listener? connection)
    (internal/require-connection :portable.tcp/close! connection))
  (internal/terminate! connection :closed nil))
