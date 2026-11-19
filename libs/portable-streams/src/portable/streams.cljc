(ns portable.streams
  "Asynchronous unsigned byte streams with bounded memory pipes and transfers."
  (:require [portable.streams.internal :as internal]
            [portable.streams.memory :as memory]
            [portable.streams.pipe :as transfer]))

(defn open-pipe!
  "Return {:source readable-handle :sink writable-handle} sharing a bounded byte buffer.
  :capacity defaults to 65536; range 1 through 1048576. Writes wait for readers when full."
  ([] (open-pipe! {}))
  ([options]
   (internal/validate-options :portable.streams/open-pipe! options #{:capacity})
   (let [capacity (:capacity options 65536)]
     (when-not (and (integer? capacity) (<= 1 capacity 1048576))
       (internal/invalid! :portable.streams/open-pipe! :capacity :out-of-range))
     (memory/open-pipe! capacity))))

(defn open-source!
  "Return a finite readable handle over an immutable byte vector, at most 16777216 bytes."
  [data]
  (internal/require-bytes :portable.streams/open-source! data 16777216)
  (memory/open-source! data))

(defn read!
  "Read asynchronously. Callback: (error nonempty-byte-vector-or-nil). Nil means EOF.
  :max-bytes defaults to 65536; range 1 through 1048576. One read may be pending. Return nil."
  ([source callback] (read! source {} callback))
  ([source options callback]
   (internal/validate-options :portable.streams/read! options #{:max-bytes})
   (internal/validate-callback :portable.streams/read! callback)
   (let [size (:max-bytes options 65536)]
     (when-not (and (integer? size) (<= 1 size 1048576))
       (internal/invalid! :portable.streams/read! :max-bytes :out-of-range))
     (internal/require-stream :portable.streams/read! source :source)
     (internal/start! source :read {:max-bytes size} nil callback nil))))

(defn write!
  "Write up to 1048576 bytes. Callback: (error accepted-byte-count). Empty writes succeed with zero.
  One write or finish may be pending. Prefixes may be read before write completion. Return nil."
  [sink data callback]
  (internal/validate-callback :portable.streams/write! callback)
  (internal/require-bytes :portable.streams/write! data 1048576)
  (internal/require-stream :portable.streams/write! sink :sink)
  (internal/start! sink :write {} data callback nil))

(defn finish!
  "Finish output gracefully. Buffered bytes drain before EOF. Callback: (error nil). Return nil.
  Wait for pending writes first. Repeating finish succeeds; later writes fail with :output-closed."
  [sink callback]
  (internal/validate-callback :portable.streams/finish! callback)
  (internal/require-stream :portable.streams/finish! sink :sink)
  (internal/start! sink :finish {} nil callback nil))

(defn close!
  "Abort a stream and discard buffered data. Closing either end of a memory pipe aborts both ends.
  Pending operations fail with :closed. Safe to repeat. Return nil."
  [endpoint]
  (internal/require-stream :portable.streams/close! endpoint nil)
  (internal/close! endpoint))

(defn pipe!
  "Copy source to sink with one chunk in flight. Return an idempotent cancellation function.
  Callback: (error copied-byte-count). On error/cancellation abort both owned ends; no partial result.
  Options: :chunk-bytes (default 65536, maximum 1048576), :max-bytes (default 9007199254740991),
  :finish? (default true). Success finishes output and releases ownership; source remains at EOF."
  ([source sink callback] (pipe! source sink {} callback))
  ([source sink options callback]
   (internal/validate-options :portable.streams/pipe! options #{:chunk-bytes :max-bytes :finish?})
   (internal/validate-callback :portable.streams/pipe! callback)
   (let [parsed (merge {:chunk-bytes 65536 :max-bytes 9007199254740991 :finish? true} options)]
     (doseq [[key minimum maximum] [[:chunk-bytes 1 1048576] [:max-bytes 0 9007199254740991]]]
       (when-not (and (integer? (key parsed)) (<= minimum (key parsed) maximum))
         (internal/invalid! :portable.streams/pipe! key :out-of-range)))
     (when-not (boolean? (:finish? parsed)) (internal/invalid! :portable.streams/pipe! :finish? :expected-boolean))
     (internal/require-stream :portable.streams/pipe! source :source)
     (internal/require-stream :portable.streams/pipe! sink :sink)
     (when (identical? (:resource source) (:resource sink))
       (internal/invalid! :portable.streams/pipe! :sink :same-stream))
     (transfer/pipe! source sink parsed callback))))
