(ns portable.tcp
  "Asynchronous TCP clients with error-first callbacks and bounded byte chunks."
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
  "Abort a connection and settle pending callbacks with :closed errors. Safe to repeat. Return nil."
  [connection]
  (require-supported :portable.tcp/close!)
  (internal/require-connection :portable.tcp/close! connection)
  (internal/terminate! connection :closed nil))
