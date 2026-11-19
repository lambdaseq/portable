(ns portable.process
  "Asynchronous native processes with portable byte-stream stdio. Browsers are unsupported."
  (:require [portable.bytes.operation :as operation]
            [portable.process.internal :as internal]
            #?(:clj [portable.process.jvm :as backend] :cljs [portable.process.node :as backend])))

(defn- require-supported [action]
  (when-not #?(:clj true :cljs (backend/supported?) :default false)
    (throw (ex-info "Processes are unsupported on this runtime."
                    {:type :portable/unsupported-operation :operation action}))))

(defn spawn!
  "Start :command argv asynchronously. Return a handle immediately. Callback: (error process).
  Options: :command (nonempty string vector), :cwd, :env (inherited overrides; nil removes a variable)."
  [options callback]
  (let [parsed (internal/prepare-spawn options callback)]
    (require-supported :portable.process/spawn!)
    (let [process (internal/create-process! callback backend/terminate!)]
      (operation/dispatch!
       #(try (backend/start! parsed (partial internal/started! process)
                             (partial internal/exited! process) (partial internal/failed! process))
              (catch #?(:clj Exception :cljs :default) error (internal/failed! process error))))
      process)))

(defn get-stdio
  "Return {:stdin sink :stdout source :stderr source} after startup, including after exit.
  Endpoints use portable.streams. Exiting preserves readable output; close! discards it."
  [process]
  (require-supported :portable.process/get-stdio)
  (internal/require-process :portable.process/get-stdio process)
  (let [state @(:state process)]
    (or (:stdio state)
        (throw (internal/make-error :portable.process/get-stdio
                                    (if (= :failed (:status state)) :spawn-failed :not-started) (:failure state))))))

(defn wait!
  "Wait asynchronously for {:exit-code integer-or-nil :signal string-or-nil}. Return a cancel function.
  Multiple waits are allowed. Cancelling a wait does not terminate the child. Results are cached."
  [process callback]
  (internal/validate-callback :portable.process/wait! callback)
  (require-supported :portable.process/wait!)
  (internal/require-process :portable.process/wait! process)
  (internal/wait! process callback))

(defn terminate!
  "Request native termination. Callback: (error nil). Return nil. Use wait! to confirm exit.
  :force? defaults to false. Requests during startup wait for native process creation."
  ([process callback] (terminate! process {} callback))
  ([process options callback]
   (internal/validate-options :portable.process/terminate! options #{:force?})
   (internal/validate-callback :portable.process/terminate! callback)
   (let [force? (:force? options false)]
     (when-not (boolean? force?) (internal/invalid! :portable.process/terminate! :force? :expected-boolean))
     (require-supported :portable.process/terminate!)
     (internal/require-process :portable.process/terminate! process)
     (internal/terminate! process force? callback))))

(defn close!
  "Request forced termination and close all stdio. Safe to repeat. Return nil.
  Startup cancelled this way reports :closed; any late child is terminated and reaped. Waits still settle."
  [process]
  (require-supported :portable.process/close!)
  (internal/require-process :portable.process/close! process)
  (internal/close! process))
