(ns portable.http.control
  "Private HTTP callback, deadline, and cancellation lifecycle."
  (:require [portable.http.message :as message]
            [portable.tcp.internal :as transport]))

(defn normalize-error [error]
  (if (= :portable/http-error (:type (ex-data error)))
    error
    (message/make-error (or (:reason (ex-data error)) :network-error) error)))

(defn finish! [control error response]
  (when (compare-and-set! (:completed control) false true)
    (when-let [timer @(:timer control)] (transport/cancel-scheduled! timer))
    (try (@(:abort control)) (catch #?(:clj Exception :cljs :default) _ nil))
    (transport/dispatch! #((:callback control) error response)))
  nil)

(defn create-control! [request callback]
  (let [control {:completed (atom false) :timer (atom nil) :abort (atom (fn [])) :callback callback}
        timer (transport/schedule! (:timeout-ms request)
                                  #(finish! control (message/make-error :timeout nil) nil))]
    (reset! (:timer control) timer)
    (when @(:completed control) (transport/cancel-scheduled! timer))
    control))

(defn set-abort! [control abort!]
  (reset! (:abort control) abort!)
  (when @(:completed control) (abort!)))

(defn cancel! [control]
  (finish! control (message/make-error :cancelled nil) nil))
