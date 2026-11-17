(ns portable.test-reporter
  #?(:cljs (:require [cljs.test :as test])))

#?(:clj
   (defn exit-on-failure! [result]
     (when (pos? (+ (:fail result) (:error result)))
       (System/exit 1))))

#?(:cljs
   (defmethod test/report [:cljs.test/default :end-run-tests] [result]
     (when (exists? js/process)
       (set! (.-exitCode js/process) (if (test/successful? result) 0 1)))
     (when (exists? js/document)
       (let [body (.-body js/document)
             passed? (and (pos? (:test result))
                          (test/successful? result)
                          (not= "failed" (.getAttribute body "data-test-status")))]
         (.setAttribute body "data-test-status" (if passed? "passed" "failed"))))))
