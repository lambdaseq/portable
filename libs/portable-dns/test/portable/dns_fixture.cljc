(ns portable.dns-fixture
  "Independent loopback UDP/TCP DNS server process."
  (:require [portable.process-fixture :as process]
            [portable.tcp.internal :as transport]))

(defn is-browser? [] #?(:clj false :cljs (exists? js/window)))

(defn after! [ms f]
  (let [timer (transport/schedule! ms f)] #(transport/cancel-scheduled! timer)))

(defn start! [ready!]
  (process/start! ["node" "test/fixtures/server.cjs"] 1
                  (fn [[port] stop!] (ready! {:server {:host "127.0.0.1" :port port} :stop! stop!}))))
