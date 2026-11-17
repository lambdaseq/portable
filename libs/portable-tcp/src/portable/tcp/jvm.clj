(ns portable.tcp.jvm
  "JVM and Babashka socket adapter."
  (:require [portable.tcp.internal :as internal])
  (:import [java.net InetSocketAddress Socket]))

(defn- start-operation! [connection slot pending perform!]
  (internal/dispatch!
   (fn []
     (let [result (try {:value (perform!)} (catch Exception error {:error error}))]
       (if-let [error (:error result)]
         (internal/terminate! connection (internal/get-error-reason error) error slot (:token pending))
         (internal/complete! connection slot (:token pending) nil (:value result))))))
  nil)

(defn connect! [options callback]
  (let [socket (Socket.)
        connection (internal/create-connection! socket #(.close socket) options callback)
        pending (internal/get-pending connection :connect)]
    (when pending
      (start-operation! connection :connect pending
                        (fn []
                          (.connect socket (InetSocketAddress. ^String (:host options) (int (:port options)))
                                    (int (:timeout-ms options)))
                          (.setTcpNoDelay socket true)
                          connection)))
    connection))

(defn read! [connection options callback]
  (when-let [pending (internal/reserve! connection :read (internal/get-operation connection :read!) options callback)]
    (start-operation!
     connection :read pending
     (fn []
       (let [buffer (byte-array (:max-bytes options))
             count (.read (.getInputStream ^Socket (internal/get-native connection)) buffer)]
         (if (= -1 count)
           (do (swap! (:state connection) assoc :eof? true) nil)
           (mapv #(bit-and % 255) (take count buffer)))))))
  nil)

(defn write! [connection data options callback]
  (when-let [pending (internal/reserve! connection :write (internal/get-operation connection :write!) options callback)]
    (start-operation! connection :write pending
                      (fn []
                        (let [output (.getOutputStream ^Socket (internal/get-native connection))]
                          (.write output (byte-array (map unchecked-byte data)))
                          (.flush output)
                          (count data)))))
  nil)

(defn shutdown-output! [connection options callback]
  (when-let [pending (internal/reserve! connection :write (internal/get-operation connection :shutdown-output!) options callback)]
    (start-operation! connection :write pending
                      (fn [] (.shutdownOutput ^Socket (internal/get-native connection)) nil)))
  nil)
