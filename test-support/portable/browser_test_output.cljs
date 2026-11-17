(ns portable.browser-test-output)

(set! *print-fn*
      (fn [text]
        (.appendChild (.getElementById js/document "test-results")
                      (.createTextNode js/document text))))

(set! *print-newline* true)
