(ns portable.process-fixture
  "Native fixture processes announcing their listening ports on stdout."
  #?(:cljs (:require [clojure.string :as str]))
  #?(:clj (:import [java.io BufferedReader InputStreamReader]
                   [java.util.concurrent TimeUnit])))

(defn- parse-ports [lines amount]
  (when (and (= amount (count lines)) (every? #(and % (re-matches #"[0-9]{1,5}" %)) lines))
    (let [ports (mapv #?(:clj #(Long/parseLong %) :cljs #(js/parseInt % 10)) lines)]
      (when (every? #(<= 1 % 65535) ports) ports))))

(defn start! [command amount ready!]
  #?(:clj
     (let [process (-> (ProcessBuilder. ^java.util.List command)
                       (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT) (.start))
           reader (BufferedReader. (InputStreamReader. (.getInputStream process)))
           lines (promise)
           stop! (fn [done]
                   (.destroy process)
                   (when-not (.waitFor process 5 TimeUnit/SECONDS)
                     (.destroyForcibly process) (.waitFor process 5 TimeUnit/SECONDS))
                   (.close reader) (done))]
       (doto (Thread. ^Runnable #(deliver lines (vec (repeatedly amount (fn [] (.readLine reader))))) "portable-fixture")
         (.setDaemon true) (.start))
       (if-let [ports (parse-ports (deref lines 5000 nil) amount)]
         (ready! ports stop!)
         (do (stop! (fn [])) (throw (ex-info "Fixture failed to announce its ports." {:command command})))))
     :cljs
     (let [child (.spawn (js/require "node:child_process") (first command) (to-array (rest command))
                         #js {:stdio #js ["ignore" "pipe" "inherit"]})
           text (atom "")
           ready (atom false)
           timer (js/setTimeout #(do (.kill child) (throw (js/Error. "Fixture failed to announce its ports."))) 5000)]
       (.on child "error" (fn [error] (js/clearTimeout timer) (throw error)))
       (.on (.-stdout child) "data"
            (fn [data]
              (let [received (swap! text str (.toString data "utf8"))]
                (when (and (>= (count (re-seq #"\n" received)) amount) (compare-and-set! ready false true))
                  (js/clearTimeout timer)
                  (if-let [ports (parse-ports (vec (take amount (str/split received #"\n"))) amount)]
                    (ready! ports (fn [done]
                                    (if (or (some? (.-exitCode child)) (some? (.-signalCode child)))
                                      (done)
                                      (do (.once child "exit" (fn [& _] (done))) (.kill child)))))
                    (do (.kill child) (throw (js/Error. "Fixture announced invalid ports.")))))))))))
