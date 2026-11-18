(ns portable.udp-fixture
  "Independent native UDP echo servers for loopback contracts."
  #?(:clj (:import [java.net DatagramPacket DatagramSocket InetAddress InetSocketAddress])))

(defn after! [ms f]
  #?(:clj (let [cancelled (atom false)]
            (doto (Thread. ^Runnable (bound-fn [] (Thread/sleep ms) (when-not @cancelled (f))) "portable-udp-test")
              (.setDaemon true) (.start))
            #(reset! cancelled true))
     :cljs (let [timer (js/setTimeout f ms)] #(js/clearTimeout timer))))

(defn start! [ready!]
  #?(:clj
     (let [server (DatagramSocket. nil)]
       (.setReuseAddress server false)
       (.bind server (InetSocketAddress. (InetAddress/getByName "127.0.0.1") 0))
       (doto (Thread.
              ^Runnable
              (fn []
                (try
                  (while (not (.isClosed server))
                    (let [buffer (byte-array 65535)
                          packet (DatagramPacket. buffer (alength buffer))]
                      (.receive server packet)
                      (.send server packet)))
                  (catch java.io.IOException _ nil)))
              "portable-udp-echo")
         (.setDaemon true) (.start))
       (ready! {:host "127.0.0.1" :port (.getLocalPort server) :stop! #(.close server)}))
     :cljs
     (let [server (.createSocket (js/require "node:dgram") "udp4")]
       (.on server "error" (fn [error] (throw error)))
       (.on server "message" (fn [buffer info] (.send server buffer (.-port info) (.-address info))))
       (.bind server 0 "127.0.0.1"
              (fn [] (ready! {:host "127.0.0.1" :port (.-port (.address server)) :stop! #(.close server)}))))))
