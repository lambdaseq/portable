(ns portable.random-test-runner
  (:require [portable.random-test]
            #?(:clj [portable.test-reporter :as reporter]
               :cljs [portable.test-reporter])
            #?(:clj [clojure.test :as test]
               :cljs [cljs.test :as test :include-macros true])))

(defn -main [& _]
  #?(:clj (reporter/exit-on-failure! (test/run-tests 'portable.random-test))
     :cljs (test/run-tests 'portable.random-test)))

#?(:cljs (set! *main-cli-fn* -main))
