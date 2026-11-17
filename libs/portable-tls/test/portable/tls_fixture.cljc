(ns portable.tls-fixture
  "Loopback TLS servers shared by all process-runtime client contracts."
  (:require #?(:cljs [clojure.string :as str])
            #?(:clj [portable.tcp.internal :as transport]))
  #?(:clj (:import [java.io BufferedReader InputStreamReader]
                   [java.util.concurrent TimeUnit])))

(def PAYLOAD (vec (concat (range 256) [0 255 1])))

(defn read-ca []
  #?(:clj (slurp "test/fixtures/ca.pem")
     :cljs (.readFileSync (js/require "node:fs") "test/fixtures/ca.pem" "utf8")))

(defn after! [ms f]
  #?(:clj (let [cancelled (atom false)]
            (transport/dispatch! #(do (Thread/sleep ms) (when-not @cancelled (f))))
            #(reset! cancelled true))
     :cljs (let [timer (js/setTimeout f ms)] #(js/clearTimeout timer))))

(defn start! [mode ready!]
  #?(:clj
     (let [process (-> (ProcessBuilder. ^java.util.List ["node" "test/fixtures/server.cjs" (name mode)])
                       (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT)
                       (.start))
           reader (BufferedReader. (InputStreamReader. (.getInputStream process)))
           port (promise)
           stop! (fn [done]
                   (.destroy process)
                   (when-not (.waitFor process 5 TimeUnit/SECONDS)
                     (.destroyForcibly process)
                     (.waitFor process 5 TimeUnit/SECONDS))
                   (.close reader)
                   (done))]
       (transport/dispatch! #(deliver port (.readLine reader)))
       (let [line (deref port 5000 nil)]
         (if (and line (re-matches #"[0-9]+" line))
           (ready! {:port (Long/parseLong line) :stop! stop!})
           (do (stop! (fn [])) (throw (ex-info "TLS fixture failed to start." {}))))))
     :cljs
     (let [child (.spawn (js/require "node:child_process") "node"
                         #js ["test/fixtures/server.cjs" (name mode)]
                         #js {:stdio #js ["ignore" "pipe" "inherit"]})
           text (atom "")
           ready (atom false)
           timer (js/setTimeout #(do (.kill child) (throw (js/Error. "TLS fixture failed to start."))) 5000)]
       (.on (.-stdout child) "data"
            (fn [data]
              (let [received (swap! text str (.toString data "utf8"))]
                (when (and (str/includes? received "\n") (compare-and-set! ready false true))
                  (js/clearTimeout timer)
                  (ready! {:port (js/parseInt received 10)
                           :stop! (fn [done] (.once child "exit" done) (.kill child))}))))))))
