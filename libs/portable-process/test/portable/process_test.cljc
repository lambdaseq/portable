(ns portable.process-test
  (:require [clojure.string :as str]
            [portable.bytes :as bytes]
            #?(:clj [portable.bytes.operation :as operation])
            [portable.process :as process]
            [portable.process.internal :as internal]
            [portable.streams :as streams]
            #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer-macros [deftest is async]]))
  #?(:clj (:import [java.io File])))

(defn- is-browser? [] #?(:clj false :cljs (exists? js/window)))

(defn- run-contract! [scenario]
  #?(:clj (let [done (promise)] (scenario #(deliver done true)) (is (true? (deref done 20000 false))))
     :cljs (async done (scenario done))))

(defn- get-error-data [f & args]
  (try (apply f args) (catch #?(:clj Exception :cljs :default) error (ex-data error))))

(defn- fixture-path [name]
  #?(:clj (.getCanonicalPath (File. ^String (str "test/fixtures/" name)))
     :cljs (.resolve (js/require "node:path") (str "test/fixtures/" name))))

(defn- command [mode & args]
  (into ["node" (fixture-path "child.cjs") mode] args))

(defn- read-all! [source result callback]
  (streams/read! source {:max-bytes 8192}
                 (fn [error data]
                   (is (nil? error))
                   (when data (is (bytes/byte-vector? data)) (is (<= 1 (count data) 8192)))
                   (if (and (nil? error) data)
                     (read-all! source (into result data) callback)
                     (callback result)))))

(defn- start-watchdog! [f]
  #?(:clj (let [cancelled (atom false)]
            (operation/dispatch! #(do (Thread/sleep 10000) (when-not @cancelled (f))))
            #(reset! cancelled true))
     :cljs (let [timer (js/setTimeout f 10000)] #(js/clearTimeout timer))))

(defn- with-child! [options scenario]
  (run-contract!
   (fn [done]
     (let [handle (atom nil) finished (atom false) watchdog (atom nil)
           finish! (fn []
                     (when (compare-and-set! finished false true)
                       (when-let [cancel! @watchdog] (cancel!))
                       (when-let [child @handle] (process/close! child)) (done)))
           _ (reset! watchdog (start-watchdog! #(do (is false "Process contract timed out") (finish!))))
           child (process/spawn! options
                                 (fn [error child]
                                   (is (nil? error))
                                   (if error (finish!) (scenario child finish!))))]
       (reset! handle child)
       (when @finished (process/close! child))))))

(deftest binary-stdio-piping-eof-and-exit
  (when-not (is-browser?)
    (with-child!
      {:command (command "echo")}
      (fn [child finish!]
        (let [{:keys [stdin stdout stderr]} (process/get-stdio child)
              payload (vec (take 1048593 (cycle (range 256))))
              events (atom 0) next! #(when (= 4 (swap! events inc)) (finish!))]
          (read-all! stdout [] (fn [data] (is (= payload data)) (next!)))
          (read-all! stderr [] (fn [data] (is (= [255 0 206 187 1] data)) (next!)))
          (is (fn? (streams/pipe! (streams/open-source! payload) stdin {:chunk-bytes 32768}
                                  (fn [error copied]
                                    (is (nil? error)) (is (= (count payload) copied))
                                    (streams/write! stdin []
                                                    (fn [error _]
                                                      (is (= :output-closed (:reason (ex-data error)))) (next!)))))))
          (process/wait! child
                         (fn [error result]
                           (is (nil? error)) (is (= {:exit-code 0 :signal nil} result)) (next!))))))))

(deftest stdout-and-stderr-drain-concurrently-with-bounded-chunks
  (when-not (is-browser?)
    (with-child!
      {:command (command "volume")}
      (fn [child finish!]
        (let [{:keys [stdout stderr]} (process/get-stdio child)
              events (atom 0) next! #(when (= 3 (swap! events inc)) (finish!))]
          (read-all! stdout [] (fn [data] (is (= 262157 (count data))) (is (every? #{255} data)) (next!)))
          (read-all! stderr [] (fn [data] (is (= 262159 (count data))) (is (every? #{128} data)) (next!)))
          (process/wait! child (fn [error result] (is (nil? error)) (is (= 0 (:exit-code result))) (next!))))))))

(deftest output-remains-readable-after-exit-and-waits-are-cached
  (when-not (is-browser?)
    (with-child!
      {:command (command "exit" "23")}
      (fn [child finish!]
        (process/wait!
         child
         (fn [error result]
           (is (nil? error)) (is (= {:exit-code 23 :signal nil} result))
           (let [{:keys [stdout stderr]} (process/get-stdio child)
                 events (atom 0) next! #(when (= 3 (swap! events inc)) (finish!))]
             (read-all! stdout []
                        (fn [data]
                          (is (= [0 255 1 2] data))
                          (streams/read! stdout (fn [error value] (is (nil? error)) (is (nil? value)) (next!)))))
             (read-all! stderr [] (fn [data] (is (= "done" (bytes/decode-utf8 data))) (next!)))
             (let [cancel! (process/wait! child (fn [error value] (is (nil? error)) (is (= result value)) (next!)))]
               (is (nil? (cancel!)))))))))))

(deftest arguments-cwd-and-environment-overrides-remain-literal
  (when-not (is-browser?)
    (let [args ["" " a b " "λ🌍" "$PORTABLE_PROCESS_VALUE" "; echo untouched" "'quoted'"]
          cwd (fixture-path "") value "  λ=one\nline two  "
          inherited #?(:clj (or (System/getenv "PORTABLE_PROCESS_INHERITED") "")
                       :cljs (or (aget (.-env js/process) "PORTABLE_PROCESS_INHERITED") ""))]
      (with-child!
        {:command (apply command "metadata" args) :cwd cwd
         :env {"PORTABLE_PROCESS_VALUE" value "PORTABLE_PROCESS_REMOVED" nil}}
        (fn [child finish!]
          (let [{:keys [stdout stderr]} (process/get-stdio child)
                events (atom 0) next! #(when (= 3 (swap! events inc)) (finish!))]
            (read-all! stdout []
                       (fn [data]
                         (is (= (into [(str (count args))] (concat args [cwd value "false" inherited]))
                                (str/split (bytes/decode-utf8 data) #"\u0000" -1)))
                         (next!)))
            (read-all! stderr [] (fn [data] (is (= [] data)) (next!)))
            (process/wait! child (fn [error result] (is (nil? error)) (is (= 0 (:exit-code result))) (next!)))))))))

(deftest cancelling-one-wait-does-not-terminate-the-child
  (when-not (is-browser?)
    (with-child!
      {:command (command "delayed-exit" "7")}
      (fn [child finish!]
        (let [calls (atom 0) events (atom 0) next! #(when (= 3 (swap! events inc)) (finish!))
              cancel! (process/wait! child
                                     (fn [error value]
                                       (is (= 1 (swap! calls inc)))
                                       (is (= {:type :portable/process-error :operation :portable.process/wait! :reason :cancelled}
                                              (ex-data error)))
                                       (is (nil? value)) (next!)))]
          (is (nil? (cancel!))) (is (nil? (cancel!)))
          (doseq [_ (range 2)]
            (process/wait! child (fn [error result]
                                   (is (nil? error)) (is (= {:exit-code 7 :signal nil} result)) (next!)))))))))

(deftest startup-failures-settle-spawn-wait-and-termination
  (when-not (is-browser?)
    (doseq [options [{:command ["/portable-process-missing-executable"]}
                     {:command (command "exit") :cwd "/portable-process-missing-directory"}]]
      (run-contract!
       (fn [done]
         (let [events (atom 0) next! #(when (= 3 (swap! events inc)) (done))
               child (process/spawn! options
                                     (fn [error result]
                                       (is (= {:type :portable/process-error :operation :portable.process/spawn! :reason :spawn-failed}
                                              (ex-data error)))
                                       (is (nil? result)) (next!)))]
           (process/wait! child (fn [error result]
                                  (is (= :spawn-failed (:reason (ex-data error)))) (is (nil? result))
                                  (is (= :spawn-failed (:reason (get-error-data process/get-stdio child))))
                                  (next!)))
           (process/terminate! child (fn [error result]
                                       (is (= :spawn-failed (:reason (ex-data error)))) (is (nil? result))
                                       (process/close! child) (process/close! child) (next!)))))))))

(deftest termination-during-startup-and-cached-exit-after-close
  (when-not (is-browser?)
    (run-contract!
     (fn [done]
       (let [events (atom 0) next! #(when (= 3 (swap! events inc)) (done))
             child (process/spawn! {:command (command "hold")}
                                   (fn [error result] (is (nil? error)) (is (some? result)) (next!)))]
         (is (nil? (process/terminate! child {:force? true}
                                       (fn [error value] (is (nil? error)) (is (nil? value)) (next!)))))
         (process/wait! child
                        (fn [error result]
                          (is (nil? error)) (is (not (contains? #{0 93} (:exit-code result))))
                          (is (nil? (process/close! child))) (is (nil? (process/close! child)))
                          (process/wait! child (fn [error value] (is (nil? error)) (is (= result value)) (next!))))))))))

(deftest close-during-startup-reaps-any-late-child
  (when-not (is-browser?)
    (run-contract!
     (fn [done]
       (let [events (atom 0) calls (atom 0) next! #(when (= 2 (swap! events inc)) (done))
             child (process/spawn!
                    {:command (command "hold")}
                    (fn [error result]
                      (is (= 1 (swap! calls inc)))
                      (if error (do (is (= :closed (:reason (ex-data error)))) (is (nil? result)))
                          (is (some? result)))
                      (next!)))]
         (process/close! child) (process/close! child)
         (process/wait! child (fn [error result]
                                (is (nil? error)) (is (not (contains? #{0 93} (:exit-code result)))) (next!))))))))

(deftest close-unblocks-pending-stdio-and-wait-confirms-termination
  (when-not (is-browser?)
    (with-child!
      {:command (command "hold")}
      (fn [child finish!]
        (let [{:keys [stdin stdout stderr]} (process/get-stdio child)]
          (streams/read!
           stderr
           (fn [error ready]
             (is (nil? error)) (is (= "ready" (bytes/decode-utf8 ready)))
             (let [events (atom 0) next! #(when (= 3 (swap! events inc)) (finish!))]
               (streams/read! stdout (fn [error value]
                                       (is (= :closed (:reason (ex-data error)))) (is (nil? value)) (next!)))
               (streams/write! stdin (vec (repeat 1048576 255))
                               (fn [error value]
                                 (is (= :closed (:reason (ex-data error)))) (is (nil? value)) (next!)))
               (process/wait! child (fn [error result] (is (nil? error)) (is (not (contains? #{0 93} (:exit-code result)))) (next!)))
               #?(:clj (operation/dispatch! #(do (Thread/sleep 50) (process/close! child)))
                  :cljs (js/setTimeout #(process/close! child) 50))))))))))

(deftest normal-and-forced-termination-after-startup
  (when-not (is-browser?)
    (doseq [force? [false true]]
      (with-child!
        {:command (command (if force? "ignore-term" "hold"))}
        (fn [child finish!]
          (streams/read!
           (:stderr (process/get-stdio child))
           (fn [error ready]
             (is (nil? error)) (is (= "ready" (bytes/decode-utf8 ready)))
             (let [events (atom 0) next! #(when (= 2 (swap! events inc)) (finish!))]
               (process/wait! child
                              (fn [error result]
                                (is (nil? error)) (is (not (contains? #{0 93} (:exit-code result))))
                                #?(:clj (is (nil? (:signal result)))
                                   :cljs (is (= (if force? "SIGKILL" "SIGTERM") (:signal result))))
                                (process/terminate! child
                                                    (fn [error value] (is (nil? error)) (is (nil? value)) (next!)))))
               (process/terminate! child {:force? force?}
                                   (fn [error value] (is (nil? error)) (is (nil? value)) (next!)))))))))))

(deftest validation-and-browser-rejection
  (doseq [[options reason]
          [[nil :expected-map] [{} :invalid-command] [{:command []} :invalid-command]
           [{:command "node"} :invalid-command] [{:command [""]} :invalid-command]
           [{:command ["node" nil]} :invalid-command] [{:command ["node" "a\u0000b"]} :invalid-command]
           [{:command ["node"] :cwd ""} :invalid-cwd] [{:command ["node"] :cwd nil} :invalid-cwd]
           [{:command ["node"] :env nil} :invalid-env] [{:command ["node"] :env {"" "x"}} :invalid-env]
           [{:command ["node"] :env {"a=b" "x"}} :invalid-env] [{:command ["node"] :env {"A" 1}} :invalid-env]
           [{:command ["node"] :env {"A" "a\u0000b"}} :invalid-env]
           [{:command ["node"] :shell true} :unknown-option]]]
    (is (= {:type :portable/invalid-input :operation :portable.process/spawn! :argument (case reason
                                                                                     :invalid-command :command
                                                                                     :invalid-cwd :cwd
                                                                                     :invalid-env :env :options)
            :reason reason} (get-error-data process/spawn! options (fn [_ _])))))
  (is (= :expected-callback (:reason (get-error-data process/spawn! {:command ["node"]} nil))))
  (is (= :expected-callback (:reason (get-error-data process/wait! nil nil))))
  (is (= :expected-boolean (:reason (get-error-data process/terminate! nil {:force? :yes} (fn [_ _])))))
  (doseq [[action f args] [[:portable.process/spawn! process/spawn! [{:command ["node"]} (fn [_ _])]]
                          [:portable.process/wait! process/wait! [nil (fn [_ _])]]
                          [:portable.process/get-stdio process/get-stdio [nil]]
                          [:portable.process/terminate! process/terminate! [nil (fn [_ _])]]
                          [:portable.process/close! process/close! [nil]]]]
    (when (or (is-browser?) (not= action :portable.process/spawn!))
      (is (= (if (is-browser?) {:type :portable/unsupported-operation :operation action}
                 {:type :portable/invalid-input :operation action :argument :process :reason :invalid-process})
             (apply get-error-data f args))))))

(deftest startup-state-and-callbacks-settle-once
  (run-contract!
   (fn [done]
     (let [events (atom 0) next! #(when (= 2 (swap! events inc)) (done))
           child (internal/create-process! (fn [error _] (is (= :closed (:reason (ex-data error)))) (next!))
                                            (fn [_ _ callback] (callback nil nil)))
           failure (ex-info "Startup failed" {})]
       (when-not (is-browser?)
         (is (= :not-started (:reason (get-error-data process/get-stdio child)))))
       (internal/wait! child (fn [error _] (is (= :spawn-failed (:reason (ex-data error)))) (next!)))
       (internal/close! child)
       (internal/failed! child failure)
       (internal/failed! child failure)))))
