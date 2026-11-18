(ns portable.udp.node
  "Node datagrams with a bounded receive queue."
  (:require [portable.udp.internal :as internal]))

(def ^:private DGRAM
  (when (and (= "nodejs" *target*) (not (exists? js/window))
             (not (exists? js/importScripts)) (exists? js/require))
    (js/require "node:dgram")))

(defn supported? [] (some? DGRAM))

(defn- pump-receive! [socket]
  (when-let [pending (internal/get-pending socket :receive)]
    (when-let [datagram (internal/take-datagram! socket)]
      (internal/complete! socket :receive (:token pending) nil datagram))))

(defn bind! [options callback]
  (let [socket (internal/create-socket! #(.close %) options callback)
        pending (internal/get-pending socket :bind)]
    (try
      (let [native (.createSocket DGRAM #js {:type "udp4" :reuseAddr false})]
        (.on native "error" #(internal/terminate! socket (internal/get-error-reason %) %))
        (.on native "close" #(internal/terminate! socket :closed nil))
        (.on native "message" (fn [buffer info]
                                 (internal/enqueue! socket {:data (vec buffer) :host (.-address info) :port (.-port info)})
                                 (pump-receive! socket)))
        (.on native "listening"
             (fn []
               (try
                 (if (= :closed (:status @(:state socket)))
                   (.close native)
                   (let [address (.address native)]
                     (.setBroadcast native false)
                     (internal/complete! socket :bind (:token pending) nil
                                         {:socket socket :host (.-address address) :port (.-port address)})))
                 (catch :default error (internal/terminate! socket (internal/get-error-reason error) error)))))
        (internal/set-native! socket native)
        (when pending (.bind native #js {:address (:host options) :port (:port options) :exclusive true})))
      (catch :default error (internal/terminate! socket (internal/get-error-reason error) error)))
    socket))

(defn send! [socket data options callback]
  (when-let [pending (internal/reserve! socket :send options callback)]
    (try
      (.send @(:native socket) (js/Uint8Array. (to-array data)) (:port options) (:host options)
             (fn [error]
               (if error
                 (internal/terminate! socket (internal/get-error-reason error) error :send (:token pending))
                 (internal/complete! socket :send (:token pending) nil (count data)))))
      (catch :default error
        (internal/terminate! socket (internal/get-error-reason error) error :send (:token pending)))))
  nil)

(defn receive! [socket options callback]
  (when (internal/reserve! socket :receive options callback) (pump-receive! socket))
  nil)
