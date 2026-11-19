(ns portable.http.server-message
  "Private HTTP server request validation and response serialization."
  (:require [clojure.string :as str]
            [portable.bytes :as bytes]
            [portable.http.message :as message]
            [portable.tcp.internal :as transport]))

(defn make-error [reason cause]
  (ex-info "HTTP serving failed."
           {:type :portable/http-error :operation :portable.http/serve!
            :reason (case reason :malformed-response :malformed-request :incomplete-response :incomplete-request reason)} cause))

(defn normalize-error [error]
  (if (= :portable.http/serve! (:operation (ex-data error))) error
      (make-error (:reason (ex-data error) :network-error) error)))

(defn- invalid! [reason] (transport/throw-invalid-input :portable.http/serve! reason))

(defn prepare-options [options handler callback]
  (transport/validate-options :portable.http/serve! options #{:tls? :timeout-ms :max-body-bytes :max-response-bytes})
  (transport/validate-callback :portable.http/serve! callback)
  (when-not (fn? handler) (invalid! :expected-handler))
  (let [parsed (merge {:tls? false :max-body-bytes 1048576 :max-response-bytes 1048576}
                      (transport/parse-timeout :portable.http/serve! options))]
    (when-not (boolean? (:tls? parsed)) (invalid! :invalid-tls-mode))
    (doseq [key [:max-body-bytes :max-response-bytes]]
      (when-not (and (integer? (key parsed)) (<= 0 (key parsed) 16777216)) (invalid! :invalid-body-limit)))
    parsed))

(defn- valid-host? [host]
  (when (and (string? host) (<= 1 (count host) 300))
    (when-let [[_ port] (re-matches #"(?:[A-Za-z0-9.-]{1,253}|\[[0-9A-Fa-f:.]{2,45}\])(?::([0-9]{1,5}))?" host)]
      ;; The authority is lexical; serving does not resolve or normalize it.
      (or (nil? port) (<= 1 (message/parse-number port 10 65535 :invalid-host) 65535)))))

(defn parse-head [data]
  (let [[line & fields] (str/split (message/decode-latin1 data) #"\r\n" -1)
        [_ method target version] (re-matches #"([^ ]+) ([^ ]+) HTTP/(1\.[01])" line)]
    (when-not (and method (<= (count method) 32) (re-matches message/TOKEN_PATTERN method))
      (message/throw-protocol-error :malformed-request))
    (when (> (count target) 8192) (message/throw-protocol-error :target-too-long))
    (when-not (and (or (str/starts-with? target "/") (and (= method "OPTIONS") (= target "*")))
                   (every? #(<= 33 (message/get-code %) 126) target)
                   (not (re-find #"[#\\]" target))
                   (not (str/includes? (str/replace target #"%[0-9A-Fa-f]{2}" "") "%")))
      (message/throw-protocol-error :unsupported-target))
    (when (> (count fields) 100) (message/throw-protocol-error :too-many-headers))
    {:method method :target target :version version
     :headers (reduce (fn [result field]
                        (let [[name value] (message/parse-field field :malformed-request)]
                          (update result name (fnil conj []) value))) {} fields)}))

(defn select-framing [state request]
  (let [{:keys [method version headers]} request
        hosts (get headers "host")
        {:keys [length transfer]} (message/parse-framing headers)
        next-state (merge state request {:body-limit (:max-body-bytes state)})]
    (when (or (and (= version "1.1") (nil? hosts))
              (and hosts (or (not= 1 (count hosts)) (not (valid-host? (first hosts))))))
      (message/throw-protocol-error :invalid-host))
    (when (or (= method "CONNECT") (contains? headers "upgrade"))
      (message/throw-protocol-error :unsupported-upgrade))
    (when (contains? headers "expect") (message/throw-protocol-error :unsupported-expectation))
    (when (some #(not= "identity" (-> % message/trim-ows str/lower-case)) (get headers "content-encoding"))
      (message/throw-protocol-error :unsupported-content-encoding))
    (when (and transfer (or (not= "chunked" transfer) (= version "1.0")))
      (message/throw-protocol-error :unsupported-transfer-encoding))
    (when (and length (> length (:body-limit next-state))) (message/throw-protocol-error :body-too-large))
    (cond transfer (assoc next-state :phase :chunk-size)
          (and length (pos? length)) (assoc next-state :phase :fixed :remaining length)
          :else (assoc next-state :phase :done))))

(defn encode-response
  ([request response limit] (encode-response request response limit nil))
  ([request response limit date]
    (transport/validate-options :portable.http/serve! response #{:status :headers :body})
    (let [status (:status response 200)
          body (:body response [])
          headers (message/parse-headers (:headers response {}) :portable.http/serve!
                                         #{"content-length" "transfer-encoding" "connection" "trailer" "upgrade" "keep-alive" "proxy-connection"})]
      (when-not (and (integer? status) (<= 200 status 599)) (invalid! :invalid-status))
      (when-not (bytes/byte-vector? body) (invalid! :expected-byte-vector))
      (when (> (count body) limit) (invalid! :response-too-large))
      (when (and (#{204 205 304} status) (seq body)) (invalid! :body-not-allowed))
      (let [headers (cond-> (assoc headers "connection" "close")
                      (and date (not (contains? headers "date"))) (assoc "date" date)
                      (not (#{204 304} status)) (assoc "content-length" (str (count body))))
            head (str "HTTP/" (:version request "1.1") " " status " Response\r\n"
                      (str/join (map (fn [[name value]] (str name ": " value "\r\n")) (sort-by key headers))) "\r\n")]
        (when (> (count headers) 100) (invalid! :invalid-headers))
        (when (> (count head) 65536) (invalid! :headers-too-large))
        (into (message/encode-ascii head) (when-not (= "HEAD" (:method request)) body))))))

(defn error-status [error]
  (case (:reason (ex-data error))
    :body-too-large 413
    :target-too-long 414
    :unsupported-content-encoding 415
    :unsupported-expectation 417
    (:headers-too-large :too-many-headers) 431
    (:unsupported-upgrade :unsupported-transfer-encoding) 501
    400))
