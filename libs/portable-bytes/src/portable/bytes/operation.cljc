(ns portable.bytes.operation
  "Private asynchronous completion for native byte transformations.")

(defn dispatch! [f]
  #?(:clj (doto (Thread. ^Runnable (bound-fn [] (f)) "portable-bytes-worker") (.setDaemon true) (.start))
     :cljs (js/queueMicrotask f)))

(defn start! [request callback perform!]
  (let [completed (atom false)
        complete! (fn [error value]
                    (when (compare-and-set! completed false true)
                      ;; Caller exceptions must escape native failure handling.
                      (dispatch! #(callback error value))))]
    (dispatch! #(try (perform! request complete!)
                     (catch #?(:clj Exception :cljs :default) error (complete! error nil)))))
  nil)
