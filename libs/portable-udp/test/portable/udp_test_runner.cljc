(ns portable.udp-test-runner
  (:require [portable.udp-test]
            #?(:clj [portable.test-reporter :as reporter]
               :cljs [portable.test-reporter])
            #?(:clj [clojure.test :as test]
               :cljs [cljs.test :as test :include-macros true])))

(defn -main [& _]
  #?(:clj (reporter/exit-on-failure! (test/run-tests 'portable.udp-test))
     :cljs (test/run-tests 'portable.udp-test)))

#?(:cljs (set! *main-cli-fn* -main))
