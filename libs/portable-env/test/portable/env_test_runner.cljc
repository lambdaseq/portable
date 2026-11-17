(ns portable.env-test-runner
  (:require [portable.env-test]
            #?(:clj [portable.test-reporter :as reporter]
               :cljs [portable.test-reporter])
            #?(:clj [clojure.test :as test]
               :cljs [cljs.test :as test :include-macros true])))

(defn -main [& _]
  #?(:clj (reporter/exit-on-failure! (test/run-tests 'portable.env-test))
     :cljs (test/run-tests 'portable.env-test)))

#?(:cljs (set! *main-cli-fn* -main))
