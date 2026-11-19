(ns portable.http.message
  "Private HTTP message validation and request serialization."
  (:require [clojure.string :as str]
            [portable.bytes :as bytes]
            [portable.tcp.internal :as transport]
            [portable.tls.internal :as tls]))

(def TOKEN_PATTERN #"[!#$%&'*+.^_`|~0-9A-Za-z-]+")
(def MANAGED_HEADERS #{"host" "content-length" "transfer-encoding" "connection" "upgrade" "expect"
                       "trailer" "te" "accept-encoding" "proxy-connection"})

(defn make-error [reason cause]
  (ex-info "HTTP request failed."
           {:type :portable/http-error :operation :portable.http/request! :reason reason} cause))

(defn normalize-error [error]
  (if (= :portable/http-error (:type (ex-data error)))
    error
    (make-error (or (:reason (ex-data error)) :network-error) error)))

(defn throw-protocol-error [reason] (throw (make-error reason nil)))

(defn throw-invalid-input [reason]
  (transport/throw-invalid-input :portable.http/request! reason))

(defn get-code [character]
  #?(:clj (int character) :cljs (.charCodeAt character 0)))

(defn encode-ascii [text] (mapv get-code text))

(defn decode-latin1 [data]
  (str/join (map #?(:clj char :cljs js/String.fromCharCode) data)))

(defn parse-number [text radix limit reason]
  (when (empty? text) (throw-protocol-error reason))
  (reduce (fn [value character]
            (let [index (str/index-of "0123456789abcdef" (str/lower-case (str character)))]
              (when (or (nil? index) (>= index radix) (> index limit) (> value (quot (- limit index) radix)))
                (throw-protocol-error reason))
              (+ (* value radix) index)))
          0 text))

(defn parse-framing [headers]
  (let [lengths (get headers "content-length")
        encodings (get headers "transfer-encoding")]
    (when (or (and lengths encodings)
              (and lengths (or (not= 1 (count lengths)) (str/includes? (first lengths) ","))))
      (throw-protocol-error :ambiguous-framing))
    {:length (when lengths (parse-number (first lengths) 10 9007199254740991 :malformed-response))
     :transfer (some->> encodings (str/join ",") str/lower-case)}))

(defn trim-ows [value] (str/replace value #"^[ \t]+|[ \t]+$" ""))

(defn parse-field
  ([line] (parse-field line :malformed-response))
  ([line reason]
   (let [colon (str/index-of line ":")
         name (when colon (subs line 0 colon))
         value (when colon (subs line (inc colon)))]
     (when-not (and name (re-matches TOKEN_PATTERN name)
                    (every? #(let [code (get-code %)]
                               (or (= code 9) (<= 32 code 126) (<= 128 code 255))) value))
       (throw-protocol-error reason))
     [(str/lower-case name) (trim-ows value)])))

(defn- parse-port [port scheme]
  (if (nil? port)
    (if (= scheme "https") 443 80)
    (try
      (let [number (parse-number port 10 65535 :invalid-url)]
        (when (zero? number) (throw-invalid-input :invalid-url))
        number)
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) _
        (throw-invalid-input :invalid-url)))))

(defn- parse-url [url]
  (when-not (and (string? url) (<= 1 (count url) 8192)
                 (every? #(<= 33 (get-code %) 126) url)
                 (not (str/includes? url "\\"))
                 (not (str/includes? (str/replace url #"%[0-9A-Fa-f]{2}" "") "%")))
    (throw-invalid-input :invalid-url))
  (let [[_ raw-scheme authority resource] (re-matches #"([A-Za-z]+)://([^/?#]+)([^#]*)(?:#.*)?" url)
        scheme (some-> raw-scheme str/lower-case)
        [_ bracket-host bracket-port] (when authority (re-matches #"\[([0-9A-Fa-f:.]+)\](?::([0-9]+))?" authority))
        [_ dns-host dns-port] (when authority (re-matches #"([A-Za-z0-9.-]+)(?::([0-9]+))?" authority))
        host (or bracket-host dns-host)
        target (cond (empty? resource) "/" (str/starts-with? resource "?") (str "/" resource) :else resource)]
    (when-not (and (#{"http" "https"} scheme) host (<= (count host) 253)
                   (str/starts-with? target "/"))
      (throw-invalid-input :invalid-url))
    ;; Fetch normalizes dot segments; reject them so both adapters send the same target.
    (when (some #(#{"." ".."} (str/replace (str/lower-case %) "%2e" "."))
                (str/split (first (str/split target #"\?" 2)) #"/"))
      (throw-invalid-input :invalid-url))
    {:url (str scheme "://" authority target) :scheme scheme :host (str/lower-case host)
     :port (parse-port (or bracket-port dns-port) scheme) :authority authority :target target}))

(defn parse-content-codings [headers]
  (let [values (get headers "content-encoding")
        codings (when values (mapv #(-> % trim-ows str/lower-case) (str/split (str/join "," values) #"," -1)))]
    (when (or (> (count codings) 4) (some #(not (#{"gzip" "deflate" "identity"} %)) codings))
      (throw-protocol-error :unsupported-content-encoding))
    (mapv keyword (remove #{"identity"} codings))))

(defn parse-headers
  ([headers] (parse-headers headers :portable.http/request! MANAGED_HEADERS))
  ([headers operation managed]
    (when-not (map? headers) (transport/throw-invalid-input operation :invalid-headers))
    (when (> (count headers) 100) (transport/throw-invalid-input operation :invalid-headers))
    (reduce-kv
     (fn [result name value]
       (when-not (and (string? name) (re-matches TOKEN_PATTERN name) (string? value)
                      (every? #(let [code (get-code %)] (or (= 9 code) (<= 32 code 126))) value))
         (transport/throw-invalid-input operation :invalid-headers))
       (let [normalized (str/lower-case name)]
         (when (contains? result normalized) (transport/throw-invalid-input operation :invalid-headers))
         (when (contains? managed normalized) (transport/throw-invalid-input operation :managed-header))
         (assoc result normalized (trim-ows value))))
     {} headers)))

(defn prepare-request [options callback]
  (transport/validate-options :portable.http/request! options
                              #{:url :method :headers :body :timeout-ms :max-body-bytes :max-encoded-body-bytes :ca-certs})
  (transport/validate-callback :portable.http/request! callback)
  (let [parsed-url (parse-url (:url options))
        method (:method options :get)
        body (:body options [])
        limit (:max-body-bytes options 1048576)
        headers (parse-headers (:headers options {}))]
    (when-not (#{:get :head :post :put :patch :delete :options} method) (throw-invalid-input :invalid-method))
    (when-not (bytes/byte-vector? body) (throw-invalid-input :expected-byte-vector))
    (when (> (count body) 1048576) (throw-invalid-input :request-too-large))
    (when (and (#{:get :head} method) (seq body)) (throw-invalid-input :body-not-allowed))
    (when-not (and (integer? limit) (<= 0 limit 16777216)) (throw-invalid-input :invalid-body-limit))
    (when (contains? options :ca-certs)
      (when-not (= "https" (:scheme parsed-url)) (throw-invalid-input :invalid-tls-options))
      (tls/validate-ca-certs :portable.http/request! (:ca-certs options)))
    (let [encoded-limit (:max-encoded-body-bytes options (min 16777216 (+ limit 65536)))]
      (when-not (and (integer? encoded-limit) (<= 0 encoded-limit 16777216))
        (throw-invalid-input :invalid-encoded-body-limit))
      (merge (transport/parse-timeout :portable.http/request! options) parsed-url
             {:method (str/upper-case (name method)) :headers headers :body body :max-body-bytes limit
              :max-encoded-body-bytes encoded-limit
              :encoded-limit-explicit? (contains? options :max-encoded-body-bytes)}))))

(defn encode-request [request]
  (let [{:keys [method target authority headers body]} request
        fields (cond-> (assoc headers "host" authority "connection" "close" "accept-encoding" "gzip, deflate")
                 (not (#{"GET" "HEAD"} method)) (assoc "content-length" (str (count body))))
        head (str method " " target " HTTP/1.1\r\n"
                  (str/join (map (fn [[name value]] (str name ": " value "\r\n")) (sort-by key fields)))
                  "\r\n")]
    (when (> (count head) 65536) (throw-invalid-input :request-too-large))
    (into (encode-ascii head) body)))
