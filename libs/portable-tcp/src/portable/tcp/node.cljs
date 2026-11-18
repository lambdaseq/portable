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

(defn- pump-accept! [listener queue]
  (when (internal/get-pending listener :read)
    (when-let [connection (first @queue)]
      (swap! queue #(vec (rest %)))
      (if (= :closed (:status @(:state connection)))
        (pump-accept! listener queue)
        (internal/deliver-accepted! listener connection)))))

(defn listen! [options callback]
  (let [server (.createServer NET #js {:allowHalfOpen true :pauseOnConnect true})
        queue (atom [])
        abort! (fn []
                 (try (.close server) (catch :default _ nil))
                 (doseq [connection @queue] (internal/terminate! connection :closed nil))
                 (reset! queue []))
        listener (assoc (internal/create-listener! server abort! options callback) :accept-queue queue)
        pending (internal/get-pending listener :connect)]
    (.on server "error" #(internal/terminate! listener (internal/get-error-reason %) %))
    (.on server "connection"
         (fn [socket]
           (let [base (internal/create-accepted! socket #(.destroy socket))
                 connection (assoc base :detach! (attach-events! base socket))]
             (.setNoDelay socket true)
             (if (or (= :closed (:status @(:state listener))) (>= (count @queue) (:backlog options)))
               (internal/terminate! connection :closed nil)
               (do (swap! queue conj connection) (pump-accept! listener queue))))))
    (.once server "listening"
           (fn []
             (if (= :closed (:status @(:state listener)))
               (abort!)
               (let [address (.address server)]
                 (reset! (:bound-address listener) {:host (.-address address) :port (.-port address)})
                 (internal/complete! listener :connect (:token pending) nil listener)))))
    (when pending
      (try (.listen server #js {:host (:host options) :port (:port options) :backlog (:backlog options)})
           (catch :default error (internal/terminate! listener (internal/get-error-reason error) error))))
    listener))

(defn accept! [listener options callback]
  (when (internal/reserve! listener :read :portable.tcp/accept! options callback)
    (pump-accept! listener (:accept-queue listener)))
  nil)
