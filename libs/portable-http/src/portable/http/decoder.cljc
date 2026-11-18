(ns portable.http.decoder
  "Private incremental HTTP/1.x response decoder."
  (:require [clojure.string :as str]
            [portable.http.message :as message]))

(defn create-decoder [request]
  {:phase :head :buffer [] :body [] :method (:method request) :max-body-bytes (:max-body-bytes request)
   :max-encoded-body-bytes (:max-encoded-body-bytes request (:max-body-bytes request))
   :interims 0 :trailer-size 0 :trailer-count 0})

(defn- find-sequence [data pattern]
  (let [limit (- (count data) (count pattern))]
    (loop [index 0]
      (cond (> index limit) nil
            (= pattern (subvec data index (+ index (count pattern)))) index
            :else (recur (inc index))))))

(defn- drop-buffer [state size]
  (assoc state :buffer (into [] (subvec (:buffer state) size))))

(defn- parse-field [line]
  (let [colon (str/index-of line ":")
        name (when colon (subs line 0 colon))
        value (when colon (subs line (inc colon)))]
    (when-not (and name (re-matches message/TOKEN_PATTERN name)
                   (every? #(let [code (message/get-code %)]
                              (or (= code 9) (<= 32 code 126) (<= 128 code 255))) value))
      (message/throw-protocol-error :malformed-response))
    [(str/lower-case name) (message/trim-ows value)]))

(defn- parse-head [data]
  (let [[status-line & lines] (str/split (message/decode-latin1 data) #"\r\n" -1)
        [_ version status] (re-matches #"HTTP/(1\.[01]) ([1-5][0-9]{2}) [\t\x20-\x7e\x80-\xff]*" status-line)]
    (when-not status (message/throw-protocol-error :malformed-response))
    (when (> (count lines) 100) (message/throw-protocol-error :too-many-headers))
    {:version version :status (message/parse-number status 10 599 :malformed-response)
     :headers (reduce (fn [headers line]
                        (let [[name value] (parse-field line)] (update headers name (fnil conj []) value)))
                      {} lines)}))

(defn- finish-response [state]
  (assoc state :phase :done :response {:status (:status state) :headers (:headers state) :body (:body state)}))

(defn- select-framing [state {:keys [status version headers]}]
  (let [lengths (get headers "content-length")
        encodings (get headers "transfer-encoding")
        {:keys [length transfer]} (message/parse-framing headers)
        next-state (assoc state :status status :headers headers)]
    (when (= status 101) (message/throw-protocol-error :unsupported-upgrade))
    (when (and transfer (or (not= transfer "chunked") (= version "1.0")))
      (message/throw-protocol-error :unsupported-transfer-encoding))
    (cond
      (< status 200)
      (do (when (or lengths encodings) (message/throw-protocol-error :malformed-response))
          (when (>= (:interims state) 8) (message/throw-protocol-error :too-many-informational-responses))
          (update state :interims inc))

      (and (#{301 302 303 307 308} status) (contains? headers "location"))
      (message/throw-protocol-error :redirect-blocked)

      (or (= "HEAD" (:method state)) (#{204 304} status))
      (finish-response (assoc next-state :buffer []))

      :else
      (let [codings (message/parse-content-codings headers)
            limit (if (seq codings) (:max-encoded-body-bytes state)
                      (min (:max-body-bytes state) (:max-encoded-body-bytes state)))
            framed (assoc next-state :content-codings codings :body-limit limit)]
        (cond
          transfer (assoc framed :phase :chunk-size)
          length (do (when (> length limit) (message/throw-protocol-error :body-too-large))
                     (if (zero? length) (finish-response framed)
                         (assoc framed :phase :fixed :remaining length)))
          :else (assoc framed :phase :close))))))

(defn- append-body [state size]
  (when (> (+ (count (:body state)) size) (:body-limit state))
    (message/throw-protocol-error :body-too-large))
  (-> state
      (update :body into (subvec (:buffer state) 0 size))
      (drop-buffer size)))

(defn- read-head [state]
  (if-let [end (find-sequence (:buffer state) [13 10 13 10])]
    (do (when (> (+ end 4) 65536) (message/throw-protocol-error :headers-too-large))
        (select-framing (drop-buffer state (+ end 4)) (parse-head (subvec (:buffer state) 0 end))))
    (do (when (> (count (:buffer state)) 65536) (message/throw-protocol-error :headers-too-large)) state)))

(defn- read-chunk-size [state]
  (if-let [end (find-sequence (:buffer state) [13 10])]
    (let [line (message/decode-latin1 (subvec (:buffer state) 0 end))
          [_ size] (re-matches #"([0-9A-Fa-f]+)(?:[ \t]*;[\t -~]*)?" line)]
      (when (> end 8192) (message/throw-protocol-error :headers-too-large))
      (when-not size (message/throw-protocol-error :malformed-response))
      (let [remaining (message/parse-number size 16 (- (:body-limit state) (count (:body state))) :body-too-large)]
        (assoc (drop-buffer state (+ end 2)) :phase (if (zero? remaining) :trailers :chunk-data)
               :remaining remaining)))
    (do (when (> (count (:buffer state)) 8192) (message/throw-protocol-error :headers-too-large)) state)))

(defn- read-trailer [state]
  (if-let [end (find-sequence (:buffer state) [13 10])]
    (let [size (+ (:trailer-size state) end 2)
          next-state (assoc (drop-buffer state (+ end 2)) :trailer-size size)]
      (when (> size 65536) (message/throw-protocol-error :headers-too-large))
      (if (zero? end)
        (finish-response next-state)
        (let [[name _] (parse-field (message/decode-latin1 (subvec (:buffer state) 0 end)))]
          (when (or (contains? message/MANAGED_HEADERS name) (= name "content-encoding"))
            (message/throw-protocol-error :invalid-trailer))
          (when (>= (:trailer-count state) 100) (message/throw-protocol-error :too-many-headers))
          (update next-state :trailer-count inc))))
    (do (when (> (+ (:trailer-size state) (count (:buffer state))) 65536)
          (message/throw-protocol-error :headers-too-large)) state)))

(defn- step [state]
  (case (:phase state)
    :head (read-head state)
    :chunk-size (read-chunk-size state)
    :trailers (read-trailer state)
    (:fixed :chunk-data)
    (let [size (min (:remaining state) (count (:buffer state)))
          next-state (-> state (append-body size) (update :remaining - size))]
      (if (zero? (:remaining next-state))
        (if (= :fixed (:phase state)) (finish-response next-state) (assoc next-state :phase :chunk-crlf))
        next-state))
    :chunk-crlf
    (if (< (count (:buffer state)) 2)
      state
      (do (when-not (= [13 10] (subvec (:buffer state) 0 2)) (message/throw-protocol-error :malformed-response))
          (assoc (drop-buffer state 2) :phase :chunk-size)))
    :close (append-body state (count (:buffer state)))
    :done state))

(defn feed [state data]
  (loop [current (update state :buffer into data)]
    (if (= :done (:phase current))
      (do (when (seq (:buffer current)) (message/throw-protocol-error :unexpected-data)) current)
      (let [next-state (step current)]
        (if (= current next-state) current (recur next-state))))))

(defn finish [state]
  (let [processed (feed state [])]
    (case (:phase processed)
      :done processed
      :close (finish-response processed)
      (message/throw-protocol-error :incomplete-response))))
