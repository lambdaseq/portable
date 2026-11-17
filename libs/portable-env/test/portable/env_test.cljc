(ns portable.env-test
  (:require [portable.env :as env]
            #?(:clj [clojure.test :as test :refer [deftest is]]
               :cljs [cljs.test :as test :refer-macros [deftest is]])))

(defn- get-error-data [variable]
  (try (env/get-env variable)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
         (ex-data error))))

(def ^:private UNSUPPORTED_ERROR
  {:type :portable/unsupported-operation :operation :portable.env/get-env})

(deftest environment-lookup-contract
  (doseq [[variable expected] [["PORTABLE_ENV_TEST_VALUE" "  portable-env λ=one\nline two  "]
                               ["PORTABLE_ENV_TEST_EMPTY" ""]
                               ["PORTABLE_ENV_TEST_NUMBER" "00123"]
                               ["PORTABLE_ENV_TEST_FALSE" "false"]]]
    (if #?(:clj false :cljs (exists? js/window))
      (is (= UNSUPPORTED_ERROR (get-error-data variable)))
      (is (= expected (env/get-env variable)) "Values remain strings, without trimming or coercion."))))

(deftest missing-variable-contract
  (let [variable (str "PORTABLE_ENV_TEST_MISSING_" (random-uuid))]
    (if #?(:clj false :cljs (exists? js/window))
      (is (= UNSUPPORTED_ERROR (get-error-data variable)))
      (is (nil? (env/get-env variable))))))

(deftest invalid-name-types
  (doseq [variable [nil 0 false :HOME [] {}]]
    (is (= {:type :portable/invalid-input
            :operation :portable.env/get-env
            :reason :expected-string}
           (get-error-data variable)))))

(deftest invalid-name-contents
  (doseq [variable ["" "=" "A=B" "\u0000" "A\u0000B"]]
    (is (= {:type :portable/invalid-input
            :operation :portable.env/get-env
            :reason :invalid-name}
           (get-error-data variable)))))

#?(:cljs
   (deftest browser-process-shims-are-unsupported
     (when (exists? js/window)
       (let [had-process? (exists? js/process)
             original (when had-process? js/process)]
         (try
           (set! js/globalThis.process
                 #js {:versions #js {:node "24.0.0"}
                      :env #js {:PORTABLE_ENV_TEST_VALUE "browser shim"}})
           (is (= UNSUPPORTED_ERROR (get-error-data "PORTABLE_ENV_TEST_VALUE")))
           (finally
             (if had-process?
               (set! js/globalThis.process original)
               (js-delete js/globalThis "process"))))))))

#?(:cljs
   (deftest node-lookup-ignores-inherited-properties
     (when-not (exists? js/window)
       (let [original (js/Object.getPrototypeOf (.-env js/process))
             inherited #js {:PORTABLE_ENV_TEST_INHERITED "prototype value"}]
         (try
           (js/Object.setPrototypeOf (.-env js/process) inherited)
           (is (nil? (env/get-env "PORTABLE_ENV_TEST_INHERITED")))
           (finally
             (js/Object.setPrototypeOf (.-env js/process) original)))))))
