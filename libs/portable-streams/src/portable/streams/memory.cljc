(ns portable.streams.memory
  "Private bounded memory channel with pure state transitions."
  (:require [portable.streams.internal :as internal]))

(defn- pump [state]
  (loop [state state events []]
    (let [{:keys [buffer read write capacity finished?]} state]
      (cond
        (and read (seq buffer))
        (let [size (min (:max-bytes read) (count buffer))
              rest (subvec buffer size)
              rest (if (:finite? state) rest (into [] rest))]
          (recur (assoc state :read nil :buffer (if (empty? rest) [] rest))
                 (conj events [(:callback read) nil (into [] (subvec buffer 0 size))])))

        (and read finished? (empty? buffer))
        (recur (assoc state :read nil) (conj events [(:callback read) nil nil]))

        (and write (= (:offset write) (count (:data write))))
        (recur (assoc state :write nil) (conj events [(:callback write) nil (count (:data write))]))

        (and write (< (count buffer) capacity))
        (let [size (min (- capacity (count buffer)) (- (count (:data write)) (:offset write)))
              end (+ (:offset write) size)]
          (recur (-> state (update :buffer into (subvec (:data write) (:offset write) end))
                     (assoc-in [:write :offset] end)) events))

        :else [state events]))))

(defn- transact! [channel transition]
  (loop []
    (let [before @channel [after events] (transition before)]
      (if (compare-and-set! channel before after)
        (doseq [[callback error value] events] (callback error value))
        (recur))))
  nil)

(defn- abort! [channel]
  (transact!
   channel
   (fn [state]
     [(assoc state :closed? true :buffer [] :read nil :write nil)
      (mapv (fn [pending] [(:callback pending) (ex-info "Memory channel closed." {:reason :closed}) nil])
            (keep state [:read :write]))])))

(defn- perform! [channel action options data callback]
  (transact!
   channel
   (fn [state]
     (if (:closed? state)
       [state [[callback (ex-info "Memory channel closed." {:reason :closed}) nil]]]
       (case action
         :read (pump (assoc state :read {:max-bytes (:max-bytes options) :callback callback}))
         :write (if (:finished? state)
                  [state [[callback (ex-info "Memory output finished." {:reason :output-closed}) nil]]]
                  (pump (assoc state :write {:data data :offset 0 :callback callback})))
         :finish (let [[next-state events] (pump (assoc state :finished? true))]
                   ;; A reader observing EOF must not abort an uncommitted finish.
                   [next-state (into [[callback nil nil]] events)]))))))

(defn open-pipe! [capacity]
  (let [channel (atom {:capacity capacity :buffer [] :closed? false :finished? false :read nil :write nil})
        endpoints (atom nil)
        perform (partial perform! channel)
        abort (fn []
                (abort! channel)
                (doseq [endpoint (vals @endpoints)] (internal/close! endpoint)))
        handles {:source (internal/create-endpoint! :source perform abort channel)
                 :sink (internal/create-endpoint! :sink perform abort channel)}]
    (reset! endpoints handles)
    handles))

(defn open-source! [data]
  (let [channel (atom {:capacity (count data) :buffer data :finite? true
                       :closed? false :finished? true :read nil :write nil})]
    (internal/create-endpoint! :source (partial perform! channel) #(abort! channel) channel)))
