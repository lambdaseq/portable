(ns portable.http
  "Bounded asynchronous HTTP requests over portable transports, or Fetch in browsers."
  (:require [portable.tcp.operation :as control]
            [portable.http.message :as message]
            [portable.http.transport :as transport]
            [portable.http.server :as server]
            [portable.http.server-message :as server-message]
            [portable.tcp.internal :as tcp-internal]
            [portable.tls :as tls]
            #?(:cljs [portable.http.browser :as browser])))

(defn request!
  "Perform one HTTP request. Callback receives (error {:status n :headers map :body byte-vector}).
  Return an idempotent cancellation function. Node/JVM use portable HTTP/1.1; browsers use Fetch."
  [options callback]
  (let [request (message/prepare-request options callback)
        wire (message/encode-request request)
        native? #?(:clj true :cljs (= "nodejs" *target*))]
    (if native?
      (when-not (transport/supported?)
        (throw (ex-info "Native HTTP transport is unavailable on this runtime."
                        {:type :portable/unsupported-operation :operation :portable.http/request!})))
      #?(:cljs (browser/validate-runtime request) :clj nil))
    (let [operation (control/create! (:timeout-ms request) callback message/make-error)]
      (try
        (if native?
          (transport/start! request operation wire)
          #?(:cljs (browser/start! request operation wire) :clj nil))
        (catch #?(:clj Exception :cljs :default) error
          (control/finish! operation (message/normalize-error error) nil)))
      #(control/cancel! operation))))

(defn serve!
  "Serve one HTTP request on a connected TCP handle, or a TLS handle with :tls? true.
  Handler: (request respond!), where respond! takes {:status n :headers map :body byte-vector}.
  Callback: (error nil), once response I/O finishes or serving fails. Return an idempotent cancel function.
  Options: :tls?, :timeout-ms, :max-body-bytes, :max-response-bytes. Both body limits default to 1048576."
  [connection options handler callback]
  (let [parsed (server-message/prepare-options options handler callback)]
    (when-not (transport/supported?)
      (throw (ex-info "HTTP serving is unsupported on this runtime."
                      {:type :portable/unsupported-operation :operation :portable.http/serve!})))
    (tcp-internal/require-connection :portable.http/serve! connection (if (:tls? parsed) "portable.tls" "portable.tcp"))
    (let [state @(:state connection)]
      (when-not (and (= :open (:status state)) (nil? (:read state)) (nil? (:write state))
                     (not (:output-closed? state)))
        (throw (server-message/make-error :busy nil))))
    (when (and (:tls? parsed) (not (contains? #{nil "http/1.1"} (:alpn (tls/get-session-info connection)))))
      (throw (server-message/make-error :unsupported-alpn nil)))
    (server/serve! connection parsed handler callback)))
