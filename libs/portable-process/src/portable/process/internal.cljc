(ns portable.process.internal
  "Private process startup, exit observers, and stdio ownership."
  (:require [clojure.string :as str]
            [portable.bytes.operation :as operation]
            [portable.streams :as streams]
            [portable.streams.internal :as stream]))

(def ^:private PROCESS_TAG #?(:clj (Object.) :cljs (js-obj)))

(defn invalid! [action argument reason]
  (throw (ex-info "Invalid process input."
                  {:type :portable/invalid-input :operation action :argument argument :reason reason})))

(defn validate-callback [action callback]
  (when-not (fn? callback) (invalid! action :callback :expected-callback)))

(defn validate-options [action options allowed]
  (when-not (map? options) (invalid! action :options :expected-map))
  (when (some #(not (contains? allowed %)) (keys options)) (invalid! action :options :unknown-option)))

(defn- valid-text? [value]
  (and (string? value) (not (str/includes? value "\u0000"))))

(defn prepare-spawn [options callback]
  (validate-options :portable.process/spawn! options #{:command :cwd :env})
  (validate-callback :portable.process/spawn! callback)
  (let [command (:command options) environment (:env options {})]
    (when-not (and (vector? command) (<= 1 (count command) 1024)
                   (every? valid-text? command) (seq (first command))
                   (<= (reduce + (map count command)) 1048576))
      (invalid! :portable.process/spawn! :command :invalid-command))
    (when (and (contains? options :cwd) (not (and (valid-text? (:cwd options)) (seq (:cwd options)))))
      (invalid! :portable.process/spawn! :cwd :invalid-cwd))
    (when-not (and (map? environment) (<= (count environment) 1024)
                   (every? (fn [[key value]]
                             (and (valid-text? key) (seq key) (not (str/includes? key "="))
                                  (or (nil? value) (valid-text? value)))) environment)
                   (<= (reduce + (map (fn [[key value]] (+ (count key) (count value))) environment)) 1048576))
      (invalid! :portable.process/spawn! :env :invalid-env))
    (assoc options :env environment)))

(defn make-error [action reason cause]
  (ex-info "Process operation failed." {:type :portable/process-error :operation action :reason reason} cause))

(defn create-process! [callback terminate-native!]
  {::tag PROCESS_TAG :terminate-native! terminate-native!
   :state (atom {:status :starting :native nil :stdio nil :result nil :failure nil :closed? false
                 :spawn (stream/bind-callback callback) :starters [] :waiters {}})})

(defn require-process [action process]
  (when-not (and (map? process) (identical? PROCESS_TAG (::tag process)))
    (invalid! action :process :invalid-process)))

(defn- deliver! [callback error value]
  (operation/dispatch! #(callback error value)))

(defn- close-native! [process native stdio]
  ;; Commit stream closure before termination can publish EOF to readers.
  (doseq [key [:stdout :stderr :stdin]]
    (when-let [endpoint (get stdio key)] (streams/close! endpoint)))
  (when native
    (operation/dispatch!
     #(try ((:terminate-native! process) native true (fn [_ _]))
           (catch #?(:clj Exception :cljs :default) _ nil)))))

(defn started! [process native stdio]
  (let [[before _] (swap-vals! (:state process)
                               #(if (= :starting (:status %))
                                  (assoc % :status :running :native native :stdio stdio :spawn nil :starters []) %))]
    (if (= :starting (:status before))
      (do
        (when (:closed? before) (close-native! process native stdio))
        (when-let [callback (:spawn before)] (deliver! callback nil process))
        (doseq [callback (:starters before)] (deliver! callback nil native)))
      (when-not (identical? native (:native before)) (close-native! process native stdio))))
  nil)

(defn failed! [process cause]
  (let [[before _] (swap-vals! (:state process)
                               #(if (= :starting (:status %))
                                  (assoc % :status :failed :failure cause :spawn nil :starters [] :waiters {}) %))]
    (when (= :starting (:status before))
      (when-let [callback (:spawn before)]
        (deliver! callback (make-error :portable.process/spawn! :spawn-failed cause) nil))
      (doseq [callback (:starters before)] (deliver! callback cause nil))
      (doseq [callback (vals (:waiters before))]
        (deliver! callback (make-error :portable.process/wait! :spawn-failed cause) nil))))
  nil)

(defn exited! [process result]
  (let [[before _] (swap-vals! (:state process)
                               #(if (= :running (:status %))
                                  (assoc % :status :exited :result result :waiters {}) %))]
    (when (= :running (:status before))
      (doseq [callback (vals (:waiters before))] (deliver! callback nil result))))
  nil)

(defn- on-start! [process callback]
  (let [callback (stream/bind-callback callback)
        [before _] (swap-vals! (:state process)
                               #(if (= :starting (:status %)) (update % :starters conj callback) %))]
    (case (:status before)
      :starting nil
      :failed (deliver! callback (:failure before) nil)
      (deliver! callback nil (:native before)))))

(defn wait! [process callback]
  (let [callback (stream/bind-callback callback)
        token #?(:clj (Object.) :cljs (js-obj))
        [before _] (swap-vals! (:state process)
                               #(if (#{:starting :running} (:status %)) (assoc-in % [:waiters token] callback) %))]
    (case (:status before)
      :exited (deliver! callback nil (:result before))
      :failed (deliver! callback (make-error :portable.process/wait! :spawn-failed (:failure before)) nil)
      nil)
    (fn []
      (let [[before _] (swap-vals! (:state process) #(update % :waiters dissoc token))]
        (when-let [callback (get (:waiters before) token)]
          (deliver! callback (make-error :portable.process/wait! :cancelled nil) nil)))
      nil)))

(defn terminate! [process force? callback]
  (on-start!
   process
   (fn [error native]
     (if error
       (deliver! callback (make-error :portable.process/terminate! :spawn-failed error) nil)
       (operation/start!
        {:native native :force? force?}
        (fn [error _]
          (callback (when error (make-error :portable.process/terminate! :termination-failed error)) nil))
        (fn [{:keys [native force?]} complete!]
          (if (= :exited (:status @(:state process)))
            (complete! nil nil)
            ((:terminate-native! process) native force? complete!)))))))
  nil)

(defn close! [process]
  (let [[before _] (swap-vals! (:state process) #(assoc % :closed? true :spawn nil))]
    (when-not (:closed? before)
      (when-let [callback (:spawn before)]
        (deliver! callback (make-error :portable.process/spawn! :closed nil) nil))
      (close-native! process (when-not (= :exited (:status before)) (:native before)) (:stdio before))))
  nil)
