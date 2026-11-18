(ns portable.http.browser
  "Fetch adapter with explicit browser policy and bounded decoded bodies."
  (:require [clojure.string :as str]
            [portable.tcp.operation :as control]
            [portable.http.message :as message]))

(def ^:private FORBIDDEN_HEADERS
  #{"accept-charset" "access-control-request-headers" "access-control-request-method" "cookie" "cookie2"
    "date" "dnt" "keep-alive" "origin" "permissions-policy" "referer" "set-cookie" "user-agent" "via"})

(defn validate-runtime [request]
  (when-not (and (exists? js/fetch) (exists? js/AbortController))
    (throw (ex-info "Fetch and AbortController are required."
                    {:type :portable/unsupported-operation :operation :portable.http/request!})))
  (when (contains? request :ca-certs)
    (throw (ex-info "Browser trust stores cannot be replaced by a request."
                    {:type :portable/unsupported-operation :operation :portable.http/request!
                     :reason :custom-trust-unavailable})))
  (doseq [[header value] (:headers request)]
    (when (or (contains? FORBIDDEN_HEADERS header) (str/starts-with? header "sec-")
              (str/starts-with? header "proxy-") (str/starts-with? header "access-control-request-")
              (and (#{"x-http-method" "x-http-method-override" "x-method-override"} header)
                   (some #(#{"CONNECT" "TRACE" "TRACK"} (str/upper-case (message/trim-ows %)))
                         (str/split value #","))))
      (throw (ex-info "The browser controls this request header."
                      {:type :portable/unsupported-operation :operation :portable.http/request!
                       :reason :unsupported-header :header header})))))

(defn- read-body! [request control response headers]
  (if-let [body (.-body response)]
    (if-not (fn? (.-getReader body))
      (control/finish! control (message/make-error :streaming-unavailable nil) nil)
      (let [reader (.getReader body)]
        (letfn [(read-next! [received]
                  (when-not @(:completed control)
                    (-> (.read reader)
                        (.then (fn [result]
                                 (cond
                                   (.-done result)
                                   (control/finish! control nil {:status (.-status response) :headers headers :body received})
                                   (> (+ (count received) (.-length (.-value result))) (:max-body-bytes request))
                                   (control/finish! control (message/make-error :body-too-large nil) nil)
                                   :else (read-next! (into received (.-value result))))))
                        (.catch #(control/finish! control (message/normalize-error %) nil)))))]
          (read-next! []))))
    (control/finish! control nil {:status (.-status response) :headers headers :body []})))

(defn start! [request control _wire]
  (let [aborter (js/AbortController.)
        headers (js/Headers.)
        options #js {:method (:method request) :headers headers :signal (.-signal aborter)
                     :credentials "omit" :cache "no-store" :redirect "error" :mode "cors"
                     :referrerPolicy "no-referrer"}]
    (doseq [[name value] (:headers request)] (.append headers name value))
    (when (seq (:body request)) (set! (.-body options) (js/Uint8Array. (to-array (:body request)))))
    (control/set-abort! control #(.abort aborter))
    (when-not @(:completed control)
      (-> (js/fetch (:url request) options)
          (.then (fn [response]
                   (let [values (atom {})]
                     (.forEach (.-headers response) (fn [value name] (swap! values assoc (str/lower-case name) [value])))
                     (message/parse-framing @values)
                     (read-body! request control response @values))))
          (.catch #(control/finish! control (message/normalize-error %) nil)))))
  nil)
