(ns portable.dns.client
  "Private DNS exchanges over portable UDP and TCP."
  (:require [portable.dns.codec :as codec]
            [portable.dns.framing :as framing]
            [portable.tcp :as tcp]
            [portable.tcp.operation :as operation]
            [portable.udp :as udp]
            #?(:cljs [portable.tcp.node :as node-tcp])
            #?(:cljs [portable.udp.node :as node-udp])))

(defn supported? [] #?(:clj true :cljs (and (node-tcp/supported?) (node-udp/supported?))))

(defn make-error [reason cause]
  (ex-info "DNS query failed." {:type :portable/dns-error :operation :portable.dns/query! :reason reason} cause))

(defn- fail! [control error]
  (let [reason (:reason (ex-data error) :io-error)]
    (operation/finish! control (make-error (if (= reason :datagram-too-large) :message-too-large reason) error) nil)))

(defn- parse-response [wire request id limit]
  (let [envelope (:message (codec/decode-envelope wire))]
    (cond
      (not (codec/matches-question? envelope request id)) {:mismatch? true}
      (> (count wire) limit) (throw (make-error :message-too-large nil))
      (:truncated? envelope) {:truncated? true}
      :else {:message (codec/decode-message wire)})))

(defn- read-tcp! [request control connection id state]
  (when-not @(:completed control)
    (tcp/read!
     connection {:max-bytes 65536 :timeout-ms (:timeout-ms request)}
     (fn [error data]
       (if error
         (fail! control error)
         (let [result (try {:state (if data (framing/feed state data) (framing/finish state))}
                           (catch #?(:clj Exception :cljs :default) error {:error error}))]
           (cond
             (:error result) (fail! control (:error result))
             (:message (:state result))
             (try
               (let [response (parse-response (:message (:state result)) request id (:max-message-bytes request))]
                 (cond
                   (:mismatch? response) (operation/finish! control (make-error :response-mismatch nil) nil)
                   (:truncated? response) (operation/finish! control (make-error :truncated-response nil) nil)
                   :else (operation/finish! control nil (:message response))))
               (catch #?(:clj Exception :cljs :default) error (fail! control error)))
             :else (read-tcp! request control connection id (:state result)))))))))

(defn- start-tcp! [request control wire id]
  (when-not @(:completed control)
    (let [connection (atom nil)]
      (operation/set-abort! control #(when-let [handle @connection] (tcp/close! handle)))
      (let [handle
            (tcp/connect!
             (assoc (:server request) :timeout-ms (:timeout-ms request))
             (fn [error handle]
               (cond
                 error (fail! control error)
                 @(:completed control) (tcp/close! handle)
                 :else
                 (do
                   (reset! connection handle)
                   (tcp/write!
                    handle (framing/frame-message wire) {:timeout-ms (:timeout-ms request)}
                    (fn [error _]
                      (if error
                        (fail! control error)
                        (read-tcp! request control handle id (framing/create-decoder (:max-message-bytes request))))))))))]
        (reset! connection handle)
        (when @(:completed control) (tcp/close! handle)))))
  nil)

(defn- read-udp! [request control socket wire id]
  (when-not @(:completed control)
    (udp/receive!
     socket {:max-bytes 65507 :timeout-ms (:timeout-ms request)}
     (fn [error packet]
       (if error
         (fail! control error)
         (let [data (:data packet)
               server (:server request)]
           (if-not (and (= (:host server) (:host packet)) (= (:port server) (:port packet))
                        (>= (count data) 2) (= id (codec/read-u16 data 0)))
             (read-udp! request control socket wire id)
             (try
               (let [response (parse-response data request id (min 512 (:max-message-bytes request)))]
                 (cond
                   (:mismatch? response) (read-udp! request control socket wire id)
                   (:truncated? response) (do (udp/close! socket) (start-tcp! request control wire id))
                   :else (operation/finish! control nil (:message response))))
               (catch #?(:clj Exception :cljs :default) error (fail! control error))))))))))

(defn start! [request control wire id]
  (if (= :tcp (:transport request))
    (start-tcp! request control wire id)
    (when-not @(:completed control)
      (let [socket (atom nil)]
        (operation/set-abort! control #(when-let [handle @socket] (udp/close! handle)))
        (let [handle
              (udp/bind!
               {:host "0.0.0.0" :port 0 :timeout-ms (:timeout-ms request)}
               (fn [error bound]
                 (cond
                   error (fail! control error)
                   @(:completed control) (udp/close! (:socket bound))
                   :else
                   (let [handle (:socket bound)]
                     (reset! socket handle)
                     (udp/send! handle wire (assoc (:server request) :timeout-ms (:timeout-ms request))
                                (fn [error _]
                                  (if error (fail! control error) (read-udp! request control handle wire id))))))))]
          (reset! socket handle)
          (when @(:completed control) (udp/close! handle))))))
  nil)
