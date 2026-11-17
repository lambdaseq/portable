(ns portable.tcp-fixture
  "Loopback-only native servers for shared transport contracts."
  #?(:clj (:import [java.net InetAddress ServerSocket Socket])))

(def PAYLOAD (vec (concat (range 256) [0 255 1])))

(defn start-thread! [f]
  #?(:clj (doto (Thread. ^Runnable (bound-fn [] (f)) "portable-tcp-test")
            (.setDaemon true) (.start))
     :cljs (js/queueMicrotask f)))

(defn after! [ms f]
  #?(:clj (let [cancelled (atom false)]
            (start-thread! #(do (Thread/sleep ms) (when-not @cancelled (f))))
            #(reset! cancelled true))
     :cljs (let [timer (js/setTimeout f ms)] #(js/clearTimeout timer))))

#?(:clj
   (defn- serve! [^Socket socket mode]
     (with-open [peer socket]
       (.setSoTimeout peer 5000)
       (let [input (.getInputStream peer) output (.getOutputStream peer)]
         (when (= mode :payload)
           (doseq [chunk (partition-all 3 PAYLOAD)]
             (.write output (byte-array (map unchecked-byte chunk)))
             (.flush output))
           (.shutdownOutput peer))
         (loop []
           (let [buffer (byte-array 3) size (.read input buffer)]
             (when (pos? size)
               (when (= mode :echo)
                 (.write output buffer 0 size)
                 (.flush output))
               (recur))))))))

(defn start! [mode ready!]
  #?(:clj
     (let [server (ServerSocket. 0 10 (InetAddress/getByName "127.0.0.1"))
           peers (atom [])]
       (.setSoTimeout server 5000)
       (start-thread! #(try
                         (let [peer (.accept server)]
                           (swap! peers conj peer)
                           (serve! peer mode))
                         (catch java.io.IOException _ nil)))
       (ready! {:port (.getLocalPort server)
                :stop! (fn [done]
                         (.close server)
                         (doseq [^Socket peer @peers] (.close peer))
                         (done))}))
     :cljs
     (let [net (js/require "node:net")
           peers (atom [])
           server (.createServer net #js {:allowHalfOpen true}
                                 (fn [peer]
                                   (swap! peers conj peer)
                                   (.on peer "error" (fn [_]))
                                   (.on peer "data" (fn [data] (when (= mode :echo) (.write peer data))))
                                   (.on peer "end" #(.end peer))
                                   (when (= mode :payload)
                                     (doseq [chunk (partition-all 3 PAYLOAD)]
                                       (.write peer (js/Uint8Array. (to-array chunk))))
                                     (.end peer))))]
       (.listen server 0 "127.0.0.1"
                #(ready! {:port (.-port (.address server))
                          :stop! (fn [done]
                                   (doseq [peer @peers] (.destroy peer))
                                   (.close server done))})))))
