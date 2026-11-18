(ns portable.dns.framing
  "Private bounded DNS-over-TCP length framing."
  (:require [portable.dns.codec :as codec]
            [portable.dns.names :as names]))

(defn frame-message [wire] (into (codec/encode-u16 (count wire)) wire))

(defn create-decoder [limit] {:buffer [] :length nil :limit limit})

(defn feed [state data]
  (let [buffer (into (:buffer state) data)
        length (or (:length state) (when (>= (count buffer) 2) (codec/read-u16 buffer 0)))]
    (when (and length (< length 12)) (names/fail! :invalid-frame-length))
    (when (and length (> length (:limit state))) (names/fail! :message-too-large))
    (when (and length (> (count buffer) (+ length 2))) (names/fail! :unexpected-data))
    (cond-> (assoc state :buffer buffer :length length)
      (and length (= (count buffer) (+ length 2))) (assoc :message (subvec buffer 2)))))

(defn finish [state]
  (when-not (:message state) (names/fail! :incomplete-response))
  state)
