(ns portable.env
  "Environment variable lookup on supported process runtimes."
  (:require [clojure.string :as str]))

(defn- validate-name [variable]
  (let [reason (cond
                 (not (string? variable)) :expected-string
                 (or (empty? variable)
                     (str/includes? variable "=")
                     (str/includes? variable "\u0000")) :invalid-name)]
    (when reason
      (throw (ex-info "Invalid environment variable name."
                      {:type :portable/invalid-input
                       :operation :portable.env/get-env
                       :reason reason})))))

#?(:cljs
   (defn- is-node-host? []
     (and (not (exists? js/window))
          (not (exists? js/importScripts))
          (exists? js/process)
          (some? js/process)
          (some? (.-versions js/process))
          (string? (.-node (.-versions js/process)))
          (some? (.-env js/process)))))

#?(:cljs
   (defn- throw-unsupported []
     (throw (ex-info "Environment variable lookup is unsupported on this host."
                     {:type :portable/unsupported-operation
                      :operation :portable.env/get-env}))))

(defn get-env
  "Read a variable as a string, or nil when absent. Preserve empty values.
  Names are nonempty strings without '=' or NUL. Host case rules apply.
  Browsers throw :portable/unsupported-operation, including with process shims."
  [variable]
  (validate-name variable)
  #?(:clj (System/getenv ^String variable)
     :cljs (if (is-node-host?)
             (when (js/Object.prototype.hasOwnProperty.call (.-env js/process) variable)
               (aget (.-env js/process) variable))
             (throw-unsupported))
     :default (throw (ex-info "Environment variable lookup is unsupported on this runtime."
                              {:type :portable/unsupported-operation
                               :operation :portable.env/get-env}))))
