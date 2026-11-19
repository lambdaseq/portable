(ns portable.process.node
  "Node child processes with paused readable streams and one pending stdin write."
  (:require [portable.streams.internal :as stream]))

(def ^:private CHILD_PROCESS
  (when (and (= "nodejs" *target*) (not (exists? js/window)) (not (exists? js/importScripts)) (exists? js/require))
    (js/require "node:child_process")))

(defn supported? [] (some? CHILD_PROCESS))

(defn- open-source! [native]
  (let [pending (atom nil) eof? (atom false) failure (atom nil)
        settle! (fn [error value]
                  (let [[before _] (swap-vals! pending (constantly nil))]
                    (when before ((:complete! before) error value))))
        pump! (fn []
                (when-let [{:keys [max-bytes]} @pending]
                  (cond
                    @failure (settle! @failure nil)
                    (pos? (.-readableLength native))
                    (when-let [data (.read native (min max-bytes (.-readableLength native)))]
                      (settle! nil (vec data)))
                    @eof? (settle! nil nil)
                    :else (.read native 0))))]
    (.pause native)
    (.on native "readable" pump!)
    (.on native "end" (fn [] (reset! eof? true) (pump!)))
    (.on native "error" (fn [error] (reset! failure error) (pump!)))
    (.on native "close" (fn []
                          (when-not @eof?
                            (reset! failure (ex-info "Process readable closed." {:reason :closed}))
                            (pump!))))
    (stream/create-endpoint!
     :source (fn [_ options _ complete!]
               (reset! pending (assoc options :complete! complete!)) (pump!))
     (fn [] (reset! pending nil) (.destroy native)) native)))

(defn- open-sink! [native]
  (let [pending (atom nil) failure (atom nil)
        settle! (fn [token error value]
                  (let [[before _] (swap-vals! pending #(if (identical? token (:token %)) nil %))]
                    (when (and before (identical? token (:token before))) ((:complete! before) error value))))]
    (.on native "error" (fn [error]
                          (reset! failure error)
                          (when-let [current @pending] (settle! (:token current) error nil))))
    (.on native "close" (fn []
                          (when-let [current @pending]
                            (settle! (:token current) (ex-info "Process writable closed." {:reason :closed}) nil))))
    (stream/create-endpoint!
     :sink
     (fn [action _ data complete!]
       (if @failure
         (complete! @failure nil)
         (let [token (js-obj)]
           (reset! pending {:token token :complete! complete!})
           (case action
             :write (.write native (js/Uint8Array. (to-array data))
                            (fn [error] (settle! token error (when-not error (count data)))))
             :finish (.end native (fn [error] (settle! token error nil)))))))
     (fn [] (reset! pending nil) (.destroy native)) native)))

(defn start! [options started! exited! failed!]
  (let [environment (js/Object.assign (js/Object.create nil) (.-env js/process))
        started? (atom false)]
    (doseq [[key value] (:env options)]
      (if (nil? value) (js/Reflect.deleteProperty environment key) (aset environment key value)))
    (let [native (.spawn CHILD_PROCESS (first (:command options)) (to-array (rest (:command options)))
                         #js {:cwd (:cwd options) :env environment :stdio #js ["pipe" "pipe" "pipe"] :shell false})]
      (.on native "error" (fn [error] (when-not @started? (failed! error))))
      (.once native "exit" (fn [code signal] (when @started? (exited! {:exit-code code :signal signal}))))
      (.once native "spawn"
             (fn []
               (try
                 (let [stdio {:stdin (open-sink! (.-stdin native))
                              :stdout (open-source! (.-stdout native)) :stderr (open-source! (.-stderr native))}]
                   (reset! started? true)
                   (started! native stdio))
                 (catch :default error
                   (.kill native "SIGKILL")
                   (doseq [stream [(.-stdin native) (.-stdout native) (.-stderr native)]] (when stream (.destroy stream)))
                   (failed! error)))))))
  nil)

(defn terminate! [native force? complete!]
  (let [error (atom nil) on-error #(reset! error %)]
    (.on native "error" on-error)
    (try (.kill native (if force? "SIGKILL" "SIGTERM"))
         (complete! @error nil)
         (finally (.removeListener native "error" on-error))))
  nil)
