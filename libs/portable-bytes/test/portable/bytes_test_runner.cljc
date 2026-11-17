(ns portable.bytes-test-runner
  (:require [portable.bytes-test]
            #?(:clj [portable.test-reporter :as reporter]
               :cljs [portable.test-reporter])
            #?(:clj [clojure.test :as test]
               :cljs [cljs.test :as test :include-macros true])))

(defn -main [& _]
  #?(:clj (reporter/exit-on-failure! (test/run-tests 'portable.bytes-test))
     :cljs (test/run-tests 'portable.bytes-test)))

#?(:cljs (set! *main-cli-fn* -main))
