(ns portable.streams.pipe
  "Private bounded transfer with exclusive endpoint ownership."
  (:require [portable.bytes.operation :as operation]
            [portable.streams.internal :as internal]))

(defn pipe! [source sink options callback]
  (let [callback (internal/bind-callback callback)
        owner #?(:clj (Object.) :cljs (js-obj))
        done (atom false)
        source-error (internal/claim! source owner)
        sink-error (when-not source-error (internal/claim! sink owner))
        claimed? (nil? (or source-error sink-error))
        complete! (fn [reason cause count]
                    (when (compare-and-set! done false true)
                      (when (and claimed? reason) (internal/close! source) (internal/close! sink))
                      (internal/release! source owner)
                      (internal/release! sink owner)
                      (operation/dispatch!
                       #(callback (when reason (internal/make-error :portable.streams/pipe! reason cause))
                                  (when-not reason count)))))]
    (if-let [reason (or source-error sink-error)]
      (complete! reason nil nil)
      (letfn [(fail! [error] (complete! (:reason (ex-data error) :io-error) error nil))
              (read-next! [count turn]
                (when-not @done
                  (let [remaining (- (:max-bytes options) count)
                        size (if (zero? remaining) 1 (min (:chunk-bytes options) remaining))]
                    (internal/start!
                     source :read {:max-bytes size} nil
                     (fn [error data]
                       (when-not @done
                         (cond
                           error (fail! error)
                           (nil? data)
                           (if (:finish? options)
                             (internal/start! sink :finish {} nil
                                              (fn [error _] (if error (fail! error) (complete! nil nil count))) owner)
                             (complete! nil nil count))
                           (zero? remaining) (complete! :byte-limit-exceeded nil nil)
                           :else
                           (internal/start!
                            sink :write {} data
                            (fn [error accepted]
                              (when-not @done
                                (if error (fail! error)
                                    (let [resume! #(read-next! (+ count accepted) (inc turn))]
                                      ;; Microtask-only transfers must yield to timer-driven cancellation.
                                      #?(:clj (resume!)
                                         :cljs (if (zero? (mod (inc turn) 16)) (js/setTimeout resume! 0) (resume!))))))) owner)))) owner))))]
        (operation/dispatch! #(read-next! 0 0))))
    #(do (complete! :cancelled nil nil) nil)))
