(ns portable.process.jvm
  "JVM and Babashka native processes with bounded stream adapters."
  (:require [portable.bytes.operation :as operation]
            [portable.streams.internal :as stream])
  (:import [java.io File InputStream OutputStream]
           [java.lang Process ProcessBuilder]))

(defn- close-stream! [native]
  ;; Closing a native process pipe can wait for a blocked I/O worker.
  (operation/dispatch! #(try (.close ^java.io.Closeable native) (catch Exception _ nil))))

(defn- open-source! [^InputStream native]
  (stream/create-endpoint!
   :source
   (fn [_ options _ complete!]
     (let [buffer (byte-array (:max-bytes options))
           count (.read native buffer)]
       (complete! nil (when-not (= -1 count) (mapv #(bit-and % 255) (take count buffer))))))
   #(close-stream! native) native))

(defn- open-sink! [^OutputStream native]
  (stream/create-endpoint!
   :sink
   (fn [action _ data complete!]
     (case action
       :write (do (.write native (byte-array (map unchecked-byte data))) (.flush native) (complete! nil (count data)))
       :finish (do (.close native) (complete! nil nil))))
   #(close-stream! native) native))

(defn- watch-exit! [^Process native exited!]
  (operation/dispatch!
   #(loop []
      (let [result (try {:exit-code (.waitFor native) :signal nil} (catch InterruptedException _ nil))]
        (if result (exited! result) (recur))))))

(defn start! [options started! exited! failed!]
  (try
    (let [builder (ProcessBuilder. ^java.util.List (:command options))
          environment (.environment builder)]
      (when-let [cwd (:cwd options)] (.directory builder (File. ^String cwd)))
      (doseq [[key value] (:env options)]
        (if (nil? value) (.remove environment key) (.put environment key value)))
      (let [native (.start builder)]
        (try
          (let [stdio {:stdin (open-sink! (.getOutputStream native))
                       :stdout (open-source! (.getInputStream native))
                       :stderr (open-source! (.getErrorStream native))}]
            (started! native stdio)
            (watch-exit! native exited!))
          (catch Exception error
            (.destroyForcibly native)
            (let [result {:exit-code (.waitFor native) :signal nil}]
              (failed! error)
              (exited! result))))))
    (catch Exception error (failed! error)))
  nil)

(defn terminate! [^Process native force? complete!]
  (if force? (.destroyForcibly native) (.destroy native))
  (complete! nil nil))
