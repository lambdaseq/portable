(ns portable.tcp.node
  "Node socket adapter, using paused reads and one pending write."
  (:require [portable.tcp.internal :as internal]))

(def ^:private NET
  (when (and (= "nodejs" *target*) (not (exists? js/window))
             (not (exists? js/importScripts)) (exists? js/require))
    (js/require "node:net")))

(defn supported? [] (some? NET))

(defn- pump-read! [connection]
  (when-let [pending (internal/get-pending connection :read)]
    (let [socket (internal/get-native connection)
          available (.-readableLength socket)]
      (cond
        (pos? available)
        (when-let [buffer (.read socket (min available (:max-bytes pending)))]
          (internal/complete! connection :read (:token pending) nil (vec buffer)))

        (:eof? @(:state connection))
        (internal/complete! connection :read (:token pending) nil nil)))))

(defn attach-events!
  ([connection socket] (attach-events! connection socket internal/get-error-reason))
  ([connection socket get-error-reason]
   (let [on-error #(internal/terminate! connection (get-error-reason %) %)
         on-close #(when-not (:eof? @(:state connection))
                     (internal/terminate! connection :closed nil))
         on-readable #(pump-read! connection)
         on-end (fn [] (swap! (:state connection) assoc :eof? true) (pump-read! connection))]
     (.on socket "error" on-error)
     (.on socket "close" on-close)
     (.on socket "readable" on-readable)
     (.on socket "end" on-end)
     (fn []
       ;; Queued native errors must stay handled during ownership transfer.
       (.removeListener socket "close" on-close)
       (.removeListener socket "readable" on-readable)
       (.removeListener socket "end" on-end)))))

(defn connect! [options callback]
  (let [socket (new (.-Socket NET) #js {:allowHalfOpen true :highWaterMark 65536})
        base (internal/create-connection! socket #(.destroy socket) options callback)
        connection (assoc base :detach! (attach-events! base socket))
        pending (internal/get-pending connection :connect)]
    (.once socket "connect"
           (fn []
             (.setNoDelay socket true)
             (when pending (internal/complete! connection :connect (:token pending) nil connection))))
    (when pending
      (.connect socket #js {:host (:host options) :port (:port options)}))
    connection))

(defn read! [connection options callback]
  (when (internal/reserve! connection :read (internal/get-operation connection :read!) options callback)
    (pump-read! connection))
  nil)

(defn write! [connection data options callback]
  (when-let [pending (internal/reserve! connection :write (internal/get-operation connection :write!) options callback)]
    (.write (internal/get-native connection) (js/Uint8Array. (to-array data))
            (fn [error]
              (if error
                (internal/terminate! connection (internal/get-error-reason error) error)
                (internal/complete! connection :write (:token pending) nil (count data))))))
  nil)

(defn shutdown-output! [connection options callback]
  (when-let [pending (internal/reserve! connection :write (internal/get-operation connection :shutdown-output!) options callback)]
    (.end (internal/get-native connection)
          (fn [] (internal/complete! connection :write (:token pending) nil nil))))
  nil)
