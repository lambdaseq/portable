(ns portable.udp
  "Asynchronous IPv4 unicast datagrams with error-first callbacks."
  (:require [portable.bytes :as bytes]
            [portable.udp.internal :as internal]
            #?(:clj [portable.udp.jvm :as backend]
               :cljs [portable.udp.node :as backend])))

(defn- require-supported [operation]
  (when-not #?(:clj true :cljs (backend/supported?) :default false)
    (throw (ex-info "UDP sockets are unsupported on this runtime."
                    {:type :portable/unsupported-operation :operation operation}))))

(defn bind!
  "Bind asynchronously and return an opaque socket immediately.
  Callback: (error {:socket socket :host address :port port}). Defaults: loopback, ephemeral port."
  [options callback]
  (let [operation :portable.udp/bind!
        parsed (merge {:host "127.0.0.1" :port 0}
                      (internal/validate-options operation options #{:host :port :timeout-ms} callback))]
    (internal/validate-address operation (:host parsed) true)
    (internal/validate-port operation (:port parsed) 0)
    (require-supported operation)
    (backend/bind! parsed callback)))

(defn send!
  "Send one byte vector to an IPv4 :host and :port. Callback: (error byte-count).
  Maximum payload: 65507 bytes. Completion means local acceptance, not delivery."
  [socket data options callback]
  (let [operation :portable.udp/send!
        parsed (internal/validate-options operation options #{:host :port :timeout-ms} callback)]
    (internal/validate-address operation (:host parsed) false)
    (internal/validate-port operation (:port parsed) 1)
    (when-not (bytes/byte-vector? data) (internal/throw-invalid-input operation :expected-byte-vector))
    (when (> (count data) internal/MAX_PAYLOAD) (internal/throw-invalid-input operation :datagram-too-large))
    (require-supported operation)
    (internal/require-socket operation socket)
    (backend/send! socket data parsed callback)))

(defn receive!
  "Receive one datagram. Callback: (error {:data bytes :host sender-address :port sender-port}).
  :max-bytes defaults to 65507. Oversized packets fail without returning a truncated payload."
  ([socket callback] (receive! socket {} callback))
  ([socket options callback]
   (let [operation :portable.udp/receive!
         parsed (assoc (internal/validate-options operation options #{:max-bytes :timeout-ms} callback)
                       :max-bytes (:max-bytes options internal/MAX_PAYLOAD))
         limit (:max-bytes parsed)]
     (when-not (and (integer? limit) (<= 0 limit internal/MAX_PAYLOAD))
       (internal/throw-invalid-input operation :invalid-receive-size))
     (require-supported operation)
     (internal/require-socket operation socket)
     (backend/receive! socket parsed callback))))

(defn close!
  "Close a socket and cancel pending callbacks with :closed errors. Safe to repeat. Return nil."
  [socket]
  (require-supported :portable.udp/close!)
  (internal/require-socket :portable.udp/close! socket)
  (internal/terminate! socket :closed nil))
