(ns portable.tcp.operation
  "Private deadlines and completion for protocols over portable transports."
  (:require [portable.tcp.internal :as transport]))

(defn finish! [operation error value]
  (when (compare-and-set! (:completed operation) false true)
    (when-let [timer @(:timer operation)] (transport/cancel-scheduled! timer))
    (try (@(:abort operation)) (catch #?(:clj Exception :cljs :default) _ nil))
    (transport/dispatch! #((:callback operation) error value)))
  nil)

(defn create! [timeout-ms callback make-error]
  (let [operation {:completed (atom false) :timer (atom nil) :abort (atom (fn []))
                   :callback callback :make-error make-error}
        timer (transport/schedule! timeout-ms #(finish! operation (make-error :timeout nil) nil))]
    (reset! (:timer operation) timer)
    (when @(:completed operation) (transport/cancel-scheduled! timer))
    operation))

(defn set-abort! [operation abort!]
  (reset! (:abort operation) abort!)
  (when @(:completed operation) (abort!)))

(defn cancel! [operation]
  (finish! operation ((:make-error operation) :cancelled nil) nil))
