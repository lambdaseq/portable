(ns portable.http-fixture
  "Loopback HTTP, HTTPS, and raw framing fixtures for every runtime."
  (:require #?(:cljs [clojure.string :as str])
            #?(:clj [portable.tcp.internal :as transport]))
  #?(:clj (:import [java.io BufferedReader InputStreamReader]
                   [java.util.concurrent TimeUnit])))

(def PAYLOAD [0 255 206 187 0])

(defn is-browser? [] #?(:clj false :cljs (exists? js/window)))

(defn read-ca []
  #?(:clj (slurp "../portable-tls/test/fixtures/ca.pem")
     :cljs (if (is-browser?) js/PORTABLE_HTTP_TEST_CA
               (.readFileSync (js/require "node:fs") "../portable-tls/test/fixtures/ca.pem" "utf8"))))

(defn- make-fixture [ports stop!]
  {:url (str "http://127.0.0.1:" (nth ports 0))
   :tls-url (str "https://127.0.0.1:" (nth ports 1))
   :raw-url (str "http://127.0.0.1:" (nth ports 2)) :stop! stop!})

(defn start! [ready!]
  #?(:clj
     (let [process (-> (ProcessBuilder. ^java.util.List ["node" "test/fixtures/server.cjs"])
                       (.redirectError java.lang.ProcessBuilder$Redirect/INHERIT) (.start))
           reader (BufferedReader. (InputStreamReader. (.getInputStream process)))
           ports (promise)
           stop! (fn [done]
                   (.destroy process)
                   (when-not (.waitFor process 5 TimeUnit/SECONDS)
                     (.destroyForcibly process) (.waitFor process 5 TimeUnit/SECONDS))
                   (.close reader) (done))]
       (transport/dispatch! #(deliver ports (vec (repeatedly 3 (fn [] (.readLine reader))))))
       (let [lines (deref ports 5000 nil)]
         (if (and lines (every? #(and % (re-matches #"[0-9]+" %)) lines))
           (ready! (make-fixture (mapv #(Long/parseLong %) lines) stop!))
           (do (stop! (fn [])) (throw (ex-info "HTTP fixture failed to start." {}))))))
     :cljs
     (if (is-browser?)
       (ready! (make-fixture (vec js/PORTABLE_HTTP_TEST_PORTS) (fn [done] (done))))
       (let [child (.spawn (js/require "node:child_process") "node" #js ["test/fixtures/server.cjs"]
                           #js {:stdio #js ["ignore" "pipe" "inherit"]})
             text (atom "")
             ready (atom false)
             timer (js/setTimeout #(do (.kill child) (throw (js/Error. "HTTP fixture failed to start."))) 5000)]
         (.on (.-stdout child) "data"
              (fn [data]
                (let [received (swap! text str (.toString data "utf8"))
                      lines (str/split received #"\n")]
                  (when (and (>= (count (re-seq #"\n" received)) 3) (compare-and-set! ready false true))
                    (js/clearTimeout timer)
                    (ready! (make-fixture (mapv #(js/parseInt % 10) (take 3 lines))
                                          (fn [done] (.once child "exit" done) (.kill child))))))))))))
