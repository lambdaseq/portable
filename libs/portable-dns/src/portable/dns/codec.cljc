(ns portable.dns.codec
  "Private DNS wire codec, independent of native resolvers."
  (:require [clojure.string :as str]
            [portable.dns.names :as names]
            [portable.tcp.internal :as transport]))

(def TYPES {:a 1 :ns 2 :cname 5 :soa 6 :ptr 12 :mx 15 :txt 16 :aaaa 28 :srv 33 :any 255})
(def ^:private TYPE_NAMES (into {} (map (fn [[name code]] [code name]) TYPES)))

(defn encode-u16 [value] [(quot value 256) (mod value 256)])

(defn read-u16 [data offset]
  (names/require-range data offset 2 (count data))
  (+ (* 256 (nth data offset)) (nth data (inc offset))))

(defn- read-u32 [data offset]
  (+ (* 65536 (read-u16 data offset)) (read-u16 data (+ offset 2))))

(defn prepare-question [operation options]
  (let [name (names/prepare-name operation (:name options))
        type (:type options :a)
        code (if (keyword? type) (TYPES type) type)
        recursion? (:recursion-desired? options true)]
    (when-not (and (integer? code) (<= 1 code 65535) (not (#{251 252} code)))
      (transport/throw-invalid-input operation :invalid-type))
    (when-not (boolean? recursion?) (transport/throw-invalid-input operation :invalid-recursion-flag))
    (assoc name :type (get TYPE_NAMES code code) :type-code code :class 1 :recursion-desired? recursion?)))

(defn encode-prepared [question id]
  (into (vec (concat (encode-u16 id) [(if (:recursion-desired? question) 1 0) 0 0 1 0 0 0 0 0 0]))
        (concat (:wire question) (encode-u16 (:type-code question)) [0 1])))

(defn- validate-wire [data]
  (when (> (count data) 65535) (names/fail! :message-too-large))
  (names/require-range data 0 12 (count data)))

(defn- read-header [data]
  (let [flags (read-u16 data 2)
        counts (mapv #(read-u16 data %) [4 6 8 10])]
    (when (pos? (bit-and flags 64)) (names/fail! :reserved-flags))
    (when (> (reduce + counts) 1024) (names/fail! :too-many-records))
    {:id (read-u16 data 0) :flags flags :response? (pos? (bit-and flags 32768))
     :opcode (bit-and (quot flags 2048) 15) :rcode (bit-and flags 15)
     :authoritative? (pos? (bit-and flags 1024)) :truncated? (pos? (bit-and flags 512))
     :recursion-desired? (pos? (bit-and flags 256)) :recursion-available? (pos? (bit-and flags 128))
     :counts counts}))

(defn- read-question [data state]
  (let [[name next-state] (names/read-name data state (count data))
        offset (:offset next-state)
        type (read-u16 data offset)
        class (read-u16 data (+ offset 2))]
    [{:name name :type (get TYPE_NAMES type type) :class class} (assoc next-state :offset (+ offset 4))]))

(defn- read-items [data state amount read-item]
  (loop [state state remaining amount items []]
    (if (zero? remaining)
      [items state]
      (let [[item next-state] (read-item data state)]
        (recur next-state (dec remaining) (conj items item))))))

(defn decode-envelope [data]
  (validate-wire data)
  (let [header (read-header data)
        [questions state] (read-items data {:offset 12 :name-offsets #{}} (first (:counts header)) read-question)]
    {:message (assoc header :questions questions) :state state}))

(defn- format-hex [number] #?(:clj (Long/toHexString number) :cljs (.toString number 16)))

(defn- format-ipv6 [data]
  (let [groups (mapv (fn [[a b]] (+ (* a 256) b)) (partition 2 data))
        runs (loop [index 0 best nil]
               (if (= index 8)
                 best
                 (let [end (if (zero? (nth groups index))
                             (+ index (count (take-while zero? (subvec groups index)))) index)
                       size (- end index)]
                   (recur (max (inc index) end)
                          (if (and (>= size 2) (> size (:size best 0))) {:start index :size size} best)))))]
    (if runs
      (str (str/join ":" (map format-hex (subvec groups 0 (:start runs)))) "::"
           (str/join ":" (map format-hex (subvec groups (+ (:start runs) (:size runs))))))
      (str/join ":" (map format-hex groups)))))

(defn- read-character-strings [data start end]
  (when (= start end) (names/fail! :invalid-rdata))
  (loop [offset start strings []]
    (if (= offset end)
      strings
      (let [length (nth data offset)]
        (names/require-range data (inc offset) length end)
        (recur (+ offset length 1) (conj strings (subvec data (inc offset) (+ offset length 1))))))))

(defn- read-rdata [data state type class end]
  (let [offset (:offset state)
        size (- end offset)]
    (if (not= class 1)
      [{:bytes (subvec data offset end)} (assoc state :offset end)]
      (case type
        (1 28)
        (do (when-not (= size (if (= type 1) 4 16)) (names/fail! :invalid-rdata))
            [{:address (if (= type 1) (str/join "." (subvec data offset end))
                           (format-ipv6 (subvec data offset end)))} (assoc state :offset end)])

        (2 5 12)
        (let [[name next-state] (names/read-name data state end)] [{:name name} next-state])

        15
        (do (names/require-range data offset 2 end)
            (let [[name next-state] (names/read-name data (assoc state :offset (+ offset 2)) end)]
              [{:preference (read-u16 data offset) :exchange name} next-state]))

        6
        (let [[primary state] (names/read-name data state end)
              [mailbox state] (names/read-name data state end)
              offset (:offset state)]
          (names/require-range data offset 20 end)
          [(merge {:primary primary :mailbox mailbox}
                  (zipmap [:serial :refresh :retry :expire :minimum] (map #(read-u32 data (+ offset %)) [0 4 8 12 16])))
           (assoc state :offset (+ offset 20))])

        16 [{:strings (read-character-strings data offset end)} (assoc state :offset end)]

        33
        (do (names/require-range data offset 6 end)
            (let [[target next-state] (names/read-name data (assoc state :offset (+ offset 6)) end false)]
              [{:priority (read-u16 data offset) :weight (read-u16 data (+ offset 2))
                :port (read-u16 data (+ offset 4)) :target target} next-state]))

        [{:bytes (subvec data offset end)} (assoc state :offset end)]))))

(defn- read-record [data state]
  (let [[name state] (names/read-name data state (count data))
        offset (:offset state)
        type (read-u16 data offset)
        class (read-u16 data (+ offset 2))
        ttl (read-u32 data (+ offset 4))
        length (read-u16 data (+ offset 8))
        start (+ offset 10)
        end (+ start length)]
    (names/require-range data start length (count data))
    (let [[value next-state] (read-rdata data (assoc state :offset start) type class end)]
      (when-not (= end (:offset next-state)) (names/fail! :invalid-rdata))
      [{:name name :type (get TYPE_NAMES type type) :class class :ttl ttl :data value} next-state])))

(defn decode-message [data]
  (let [{:keys [message state]} (decode-envelope data)
        [_ answers authorities additionals] (:counts message)
        [answers state] (read-items data state answers read-record)
        [authorities state] (read-items data state authorities read-record)
        [additionals state] (read-items data state additionals read-record)]
    (when-not (= (:offset state) (count data)) (names/fail! :trailing-data))
    (-> message (dissoc :counts) (assoc :answers answers :authorities authorities :additionals additionals))))

(defn matches-question? [message question id]
  (and (= id (:id message)) (:response? message) (= 0 (:opcode message))
       (= [(select-keys question [:name :type :class])] (:questions message))
       (= (:recursion-desired? question) (:recursion-desired? message))))
