(ns portable.http
  "Bounded asynchronous HTTP requests over portable transports, or Fetch in browsers."
  (:require [portable.http.control :as control]
            [portable.http.message :as message]
            [portable.http.transport :as transport]
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
    (let [operation (control/create-control! request callback)]
      (try
        (if native?
          (transport/start! request operation wire)
          #?(:cljs (browser/start! request operation wire) :clj nil))
        (catch #?(:clj Exception :cljs :default) error
          (control/finish! operation (control/normalize-error error) nil)))
      #(control/cancel! operation))))
