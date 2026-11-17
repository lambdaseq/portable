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
    (let [socket (:native connection)
          available (.-readableLength socket)]
      (cond
        (pos? available)
        (when-let [buffer (.read socket (min available (:max-bytes pending)))]
          (internal/complete! connection :read (:token pending) nil (vec buffer)))

        (:eof? @(:state connection))
        (internal/complete! connection :read (:token pending) nil nil)))))

(defn connect! [options callback]
  (let [socket (new (.-Socket NET) #js {:allowHalfOpen true :highWaterMark 65536})
        connection (internal/create-connection! socket #(.destroy socket) options callback)
        pending (internal/get-pending connection :connect)]
    (.on socket "error" #(internal/terminate! connection (internal/get-error-reason %) %))
    (.on socket "close"
         #(when-not (:eof? @(:state connection))
            (internal/terminate! connection :closed nil)))
    (.on socket "readable" #(pump-read! connection))
    (.on socket "end" (fn [] (swap! (:state connection) assoc :eof? true) (pump-read! connection)))
    (.on socket "connect"
         (fn []
           (.setNoDelay socket true)
           (when pending (internal/complete! connection :connect (:token pending) nil connection))))
    (when pending
      (.connect socket #js {:host (:host options) :port (:port options)}))
    connection))

(defn read! [connection options callback]
  (when (internal/reserve! connection :read :portable.tcp/read! options callback)
    (pump-read! connection))
  nil)

(defn write! [connection data options callback]
  (when-let [pending (internal/reserve! connection :write :portable.tcp/write! options callback)]
    (.write (:native connection) (js/Uint8Array. (to-array data))
            (fn [error]
              (if error
                (internal/terminate! connection (internal/get-error-reason error) error)
                (internal/complete! connection :write (:token pending) nil (count data))))))
  nil)

(defn shutdown-output! [connection options callback]
  (when-let [pending (internal/reserve! connection :write :portable.tcp/shutdown-output! options callback)]
    (.end (:native connection)
          (fn [] (internal/complete! connection :write (:token pending) nil nil))))
  nil)
