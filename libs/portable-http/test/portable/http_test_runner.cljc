(ns portable.http-test-runner
  (:require [portable.http-test]
            [portable.http-server-codec-test]
            [portable.http-server-test]
            #?(:clj [portable.test-reporter :as reporter]
               :cljs [portable.test-reporter])
            #?(:clj [clojure.test :as test]
               :cljs [cljs.test :as test :include-macros true])))

(defn -main [& _]
  #?(:clj (reporter/exit-on-failure! (test/run-tests 'portable.http-codec-test 'portable.http-test 'portable.http-server-codec-test 'portable.http-server-test))
     :cljs (test/run-tests 'portable.http-codec-test 'portable.http-test 'portable.http-server-codec-test 'portable.http-server-test)))

#?(:cljs (set! *main-cli-fn* -main))
