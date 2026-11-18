(ns portable.http-fixture
  "Loopback HTTP, HTTPS, and raw framing fixtures for every runtime."
  (:require [portable.process-fixture :as process]))

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
  (if (is-browser?)
    #?(:cljs (ready! (make-fixture (vec js/PORTABLE_HTTP_TEST_PORTS) (fn [done] (done)))) :clj nil)
    (process/start! ["node" "test/fixtures/server.cjs"] 3
                    (fn [ports stop!] (ready! (make-fixture ports stop!))))))
