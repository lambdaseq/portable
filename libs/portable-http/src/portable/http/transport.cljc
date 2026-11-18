(ns portable.http.transport
  "HTTP/1.1 over portable TCP/TLS, without host HTTP clients."
  (:require [portable.compression :as compression]
            [portable.tcp.operation :as control]
            [portable.http.decoder :as decoder]
            [portable.http.message :as message]
            [portable.tcp :as tcp]
            [portable.tls :as tls]
            #?(:cljs [portable.tcp.node :as node])))

(defn supported? [] #?(:clj true :cljs (node/supported?)))

(defn- close-transport! [secure? connection]
  (if secure? (tls/close! connection) (tcp/close! connection)))

(defn- read-transport! [secure? connection options callback]
  (if secure? (tls/read! connection options callback) (tcp/read! connection options callback)))

(defn- write-transport! [secure? connection data options callback]
  (if secure? (tls/write! connection data options callback) (tcp/write! connection data options callback)))

(defn decode-body! [request control response codings]
  (when-not @(:completed control)
    (if-let [coding (first codings)]
      (compression/decompress!
       coding (:body response)
       {:max-output-bytes (if (next codings) (:max-encoded-body-bytes request) (:max-body-bytes request))}
       (fn [error data]
         (if error
           (control/finish! control
                            (message/make-error (case (:reason (ex-data error))
                                                  :output-too-large :body-too-large
                                                  :compression-unavailable :compression-unavailable
                                                  :invalid-compressed-body) error) nil)
           (decode-body! request control (assoc response :body data) (next codings)))))
      (control/finish! control nil response))))

(defn- read-response! [request control connection secure? state]
  (when-not @(:completed control)
    (read-transport!
     secure? connection {:max-bytes 65536 :timeout-ms (:timeout-ms request)}
     (fn [error data]
       (if error
         (control/finish! control (message/normalize-error error) nil)
         (let [result (try {:state (if (nil? data) (decoder/finish state) (decoder/feed state data))}
                           (catch #?(:clj Exception :cljs :default) failure {:error failure}))]
           (cond
             (:error result) (control/finish! control (message/normalize-error (:error result)) nil)
             (= :done (:phase (:state result)))
             (do (close-transport! secure? connection)
                 (decode-body! request control (:response (:state result)) (reverse (:content-codings (:state result)))))
             :else (read-response! request control connection secure? (:state result)))))))))

(defn- write-request! [request control connection secure? chunks]
  (when-not @(:completed control)
    (if-let [chunk (first chunks)]
      (write-transport! secure? connection (vec chunk) {:timeout-ms (:timeout-ms request)}
                        (fn [error _]
                          (if error
                            (control/finish! control (message/normalize-error error) nil)
                            (write-request! request control connection secure? (next chunks)))))
      (read-response! request control connection secure? (decoder/create-decoder request)))))

(defn start! [request control wire]
  (let [secure? (= "https" (:scheme request))
        connection (atom nil)
        options (cond-> (select-keys request [:host :port :timeout-ms])
                  secure? (assoc :alpn ["http/1.1"])
                  (contains? request :ca-certs) (assoc :ca-certs (:ca-certs request)))
        connected! (fn [error handle]
                     (cond
                       error (control/finish! control (message/normalize-error error) nil)
                       @(:completed control) (close-transport! secure? handle)
                       :else (do (reset! connection handle)
                                 (write-request! request control handle secure? (partition-all 65536 wire)))))]
    (control/set-abort! control #(when-let [handle @connection] (close-transport! secure? handle)))
    (reset! connection (if secure? (tls/connect! options connected!) (tcp/connect! options connected!)))
    (when @(:completed control) (close-transport! secure? @connection)))
  nil)
