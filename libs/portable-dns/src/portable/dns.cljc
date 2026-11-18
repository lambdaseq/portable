(ns portable.dns
  "DNS wire codecs and bounded asynchronous queries over portable UDP/TCP."
  (:require [portable.bytes :as bytes]
            [portable.dns.client :as client]
            [portable.dns.codec :as codec]
            [portable.dns.entropy :as entropy]
            [portable.tcp.internal :as transport]
            [portable.tcp.operation :as operation]
            [portable.udp.internal :as udp]))

(defn encode-query
  "Encode one IN-class DNS question as a byte vector. Options: :id, :name, :type, :recursion-desired?."
  [options]
  (let [action :portable.dns/encode-query]
    (transport/validate-options action options #{:name :type :id :recursion-desired?})
    (when-not (and (integer? (:id options)) (<= 0 (:id options) 65535))
      (transport/throw-invalid-input action :invalid-id))
    (codec/encode-prepared (codec/prepare-question action options) (:id options))))

(defn decode-message
  "Decode a complete DNS message into flags, questions, and resource-record sections."
  [data]
  (when-not (bytes/byte-vector? data)
    (transport/throw-invalid-input :portable.dns/decode-message :expected-byte-vector))
  (codec/decode-message data))

(defn- prepare-request [options callback]
  (let [action :portable.dns/query!]
    (transport/validate-options action options
                                #{:name :type :server :transport :timeout-ms :max-message-bytes :recursion-desired?})
    (transport/validate-callback action callback)
    (let [request (transport/parse-timeout action options)
          question (codec/prepare-question action options)
          server (:server options)
          limit (:max-message-bytes options 65535)
          mode (:transport options :udp)]
      (transport/validate-options action server #{:host :port})
      (let [server (merge {:port 53} server)]
        (udp/validate-address action (:host server) false)
        (udp/validate-port action (:port server) 1)
        (when-not (#{:udp :tcp} mode) (transport/throw-invalid-input action :invalid-transport))
        (when-not (and (integer? limit) (<= 12 limit 65535))
          (transport/throw-invalid-input action :invalid-message-limit))
        (merge request question {:server server :transport mode :max-message-bytes limit})))))

(defn query!
  "Query an explicit IPv4 DNS server. UDP defaults to TCP fallback on matched truncation.
  Callback: (error decoded-message). Return an idempotent cancellation function."
  [options callback]
  (let [request (prepare-request options callback)]
    (when-not (client/supported?)
      (throw (ex-info "Raw DNS queries require UDP and TCP sockets."
                      {:type :portable/unsupported-operation :operation :portable.dns/query!})))
    (let [control (operation/create! (:timeout-ms request) callback client/make-error)]
      (try
        (let [id (entropy/generate-id!)
              wire (codec/encode-prepared request id)]
          (client/start! request control wire id))
        (catch #?(:clj Exception :cljs :default) error
          (operation/finish! control (client/make-error (:reason (ex-data error) :io-error) error) nil)))
      #(operation/cancel! control))))
