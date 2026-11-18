(ns portable.udp.jvm
  "JVM and Babashka adapter using full-size native datagram buffers."
  (:require [portable.udp.internal :as internal])
  (:import [java.net DatagramPacket DatagramSocket InetAddress InetSocketAddress]))

(defn- get-address [host]
  (InetAddress/getByAddress (byte-array (map unchecked-byte (internal/parse-address host)))))

(defn- start-operation! [socket slot pending perform!]
  (internal/dispatch!
   (fn []
     (when (identical? (:token pending) (:token (internal/get-pending socket slot)))
       (let [result (try {:value (perform!)} (catch Exception error {:error error}))]
         (if-let [error (:error result)]
           (internal/terminate! socket (internal/get-error-reason error) error slot (:token pending))
           (internal/complete! socket slot (:token pending) nil (:value result)))))))
  nil)

(defn bind! [options callback]
  (let [socket (internal/create-socket! #(.close ^DatagramSocket %) options callback)
        pending (internal/get-pending socket :bind)]
    (when pending
      (start-operation!
       socket :bind pending
       (fn []
         (let [native (DatagramSocket. nil)]
           (internal/set-native! socket native)
           (.setReuseAddress native false)
           (.setBroadcast native false)
           (.bind native (InetSocketAddress. (get-address (:host options)) (int (:port options))))
           {:socket socket :host (:host options) :port (.getLocalPort native)}))))
    socket))

(defn send! [socket data options callback]
  (when-let [pending (internal/reserve! socket :send options callback)]
    (start-operation!
     socket :send pending
     (fn []
       (let [buffer (byte-array (map unchecked-byte data))
             packet (DatagramPacket. buffer (count data) (get-address (:host options)) (int (:port options)))]
         (.send ^DatagramSocket @(:native socket) packet)
         (count data)))))
  nil)

(defn receive! [socket options callback]
  (when-let [pending (internal/reserve! socket :receive options callback)]
    (start-operation!
     socket :receive pending
     (fn []
       ;; Read the complete packet before enforcing the caller's limit; native receives truncate silently.
       (let [buffer (byte-array 65535)
             packet (DatagramPacket. buffer (alength buffer))]
         (loop []
           (.setLength packet (alength buffer))
           (.receive ^DatagramSocket @(:native socket) packet)
           (let [host (.getHostAddress ^InetAddress (.getAddress packet))]
             ;; A JVM wildcard socket can also accept IPv6; the public contract exposes IPv4 only.
             (if (internal/parse-address host)
               {:data (mapv #(bit-and % 255) (take (.getLength packet) buffer)) :host host :port (.getPort packet)}
               (recur))))))))
  nil)
