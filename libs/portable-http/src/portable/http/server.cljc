(ns portable.http.server
  "Private one-request HTTP/1.1 serving over connected portable transports."
  (:require [portable.clock :as clock]
            [portable.http.decoder :as decoder]
            [portable.http.server-message :as message]
            [portable.tcp :as tcp]
            [portable.tcp.internal :as transport]
            [portable.tcp.operation :as control]
            [portable.tls :as tls])
  #?(:clj (:import [java.time Instant ZoneOffset]
                   [java.time.format DateTimeFormatter]
                   [java.util Locale])))

(defn- encode-response! [request response limit]
  (let [date #?(:clj (.format (.withZone (DateTimeFormatter/ofPattern "EEE, dd MMM uuuu HH:mm:ss 'GMT'" Locale/US)
                                        ZoneOffset/UTC) (Instant/ofEpochMilli (clock/now-ms)))
                :cljs (.toUTCString (js/Date. (clock/now-ms))))]
    (message/encode-response request response limit date)))

(defn- write-response! [connection options operation wire outcome]
  (let [write! (if (:tls? options) tls/write! tcp/write!)
        shutdown! (if (:tls? options) tls/shutdown-output! tcp/shutdown-output!)]
    (letfn [(write-next! [chunks]
              (when-not @(:completed operation)
                (if-let [chunk (first chunks)]
                  (write! connection (vec chunk) {:timeout-ms (:timeout-ms options)}
                          (fn [error _]
                            (if error
                              (control/finish! operation (or outcome (message/normalize-error error)) nil)
                              (write-next! (next chunks)))))
                  (shutdown! connection {:timeout-ms (:timeout-ms options)}
                             (fn [error _]
                               (control/finish! operation (or outcome (when error (message/normalize-error error))) nil))))))]
      (write-next! (partition-all 65536 wire)))))

(defn- reject! [connection options operation error]
  (write-response! connection options operation
                   (encode-response! {} {:status (message/error-status error)} 0) error))

(defn- invoke-handler! [connection options operation request handler]
  (let [responded (atom false)
        respond! (fn [response]
                   (when (and (not @(:completed operation)) (not @responded))
                     (let [wire (encode-response! request response (:max-response-bytes options))]
                       (when (compare-and-set! responded false true)
                         (write-response! connection options operation wire nil))))
                   nil)]
    (transport/dispatch!
     (fn []
       (when-not @(:completed operation)
         (try (handler request respond!)
              (catch #?(:clj Exception :cljs :default) error
                (when (and (not @(:completed operation)) (compare-and-set! responded false true))
                  (write-response! connection options operation
                                   (encode-response! request {:status 500} 0)
                                   (message/make-error :handler-failed error))))))))))

(defn- read-request! [connection options operation state handler]
  (when-not @(:completed operation)
    ((if (:tls? options) tls/read! tcp/read!)
     connection {:max-bytes 65536 :timeout-ms (:timeout-ms options)}
     (fn [error data]
       (if error
         (control/finish! operation (message/normalize-error error) nil)
         (let [result (try {:state (if (nil? data) (decoder/finish state) (decoder/feed state data))}
                           (catch #?(:clj Exception :cljs :default) error {:error (message/normalize-error error)}))]
           (cond
             (:error result) (reject! connection options operation (:error result))
             (= :done (:phase (:state result)))
             (invoke-handler! connection options operation (:request (:state result)) handler)
             :else (read-request! connection options operation (:state result) handler))))))))

(defn serve! [connection options handler callback]
  (let [close! (if (:tls? options) tls/close! tcp/close!)
        operation (control/create! (:timeout-ms options) callback message/make-error)]
    (control/set-abort! operation #(close! connection))
    (try (read-request! connection options operation (decoder/create-request-decoder options) handler)
         (catch #?(:clj Exception :cljs :default) error
           (control/finish! operation (message/normalize-error error) nil)))
    #(control/cancel! operation)))
