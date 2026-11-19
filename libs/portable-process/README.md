# portable-process

`lambdaseq/portable-process` provides native child-process startup, async exit waits, termination, and byte-stream stdio through `portable.process`.
JVM Clojure, Babashka, and Node use native processes. Browsers reject every public operation explicitly.
The library depends on `portable-streams` and `portable-bytes`. The `lambdaseq/portable` umbrella includes it.

```clojure
(require '[portable.process :as process]
         '[portable.streams :as streams]
         '[portable.bytes :as bytes])

(defn drain! [source complete!]
  (streams/read! source
    (fn [error chunk]
      (cond
        error (complete! error nil)
        chunk (do (println (pr-str chunk)) (drain! source complete!))
        :else (complete! nil nil)))))

(def child
  (process/spawn! {:command ["node" "-e" "process.stdin.pipe(process.stdout)"]}
    (fn [error child]
      (if error
        (println (ex-data error))
        (let [{:keys [stdin stdout stderr]} (process/get-stdio child)
              completed (atom 0)
              complete! (fn [error value]
                          (when error (println (ex-data error)) (process/close! child))
                          (when value (println (pr-str value)))
                          (when (= 4 (swap! completed inc)) (process/close! child)))]
          (drain! stdout complete!)
          (drain! stderr complete!)
          (streams/pipe! (streams/open-source! (bytes/encode-utf8 "hello λ\n")) stdin complete!)
          (process/wait! child complete!))))))
```

This example requires Node on PATH. Application commands are executable-and-argument vectors.
JVM scripts must keep their process alive until child processes and callbacks finish.
Drain stdout and stderr concurrently. Waiting for exit before consuming a large output can block the child on its native pipe capacity.

## API contract

| Function | Immediate return | Successful callback value |
| --- | --- | --- |
| `(spawn! options callback)` | Opaque process handle | The started process handle |
| `(get-stdio child)` | `{:stdin sink :stdout source :stderr source}` | No callback |
| `(wait! child callback)` | Idempotent wait-cancellation function | `{:exit-code integer-or-nil :signal string-or-nil}` |
| `(terminate! child options callback)` | nil | nil, once the native termination request is accepted |
| `(close! child)` | nil | No callback |

Options are optional for `terminate!`. Callbacks receive `(error result)` asynchronously and complete once.
Failures supply an error and nil as the result. Nonzero exit codes are ordinary successful wait results.
JVM callbacks run on daemon workers and can run before the initiating function returns.
They retain the initiating callback's dynamic bindings. Node callbacks run through its event loop.
Caller callback exceptions escape native error handling and do not trigger another callback.
Handles are live resources; do not inspect, alter, copy, or serialize their contents.

### Startup options

| Option | Contract |
| --- | --- |
| `:command` | Required nonempty vector containing the executable followed by arguments |
| `:cwd` | Optional nonempty working-directory path; defaults to the parent's working directory |
| `:env` | Optional environment override map; defaults to empty, preserving the inherited environment |

The command contains 1 through 1024 strings, totalling at most 1048576 characters.
The executable string must be nonempty. Empty arguments, spaces, quotes, Unicode, and shell metacharacters are preserved as argument data.
Command strings and cwd reject NUL. A shell is not inserted; shell execution requires explicitly naming a shell executable and arguments.
Unknown options, including `:shell`, are rejected.

Environment names are nonempty strings without `=` or NUL. Values are strings without NUL, or nil to remove an inherited variable.
An override map contains at most 1024 entries and 1048576 characters across names and values.
Parent environment state is never changed. Empty string values remain present and empty.
Executable search, environment-name case rules, path syntax, and native OS size limits remain runtime-dependent.
Absolute executable and working-directory paths avoid reliance on executable search and relative-path resolution.

`spawn!` returns before native startup finishes. Use its successful callback before obtaining stdio.
`get-stdio` throws `:not-started` before startup and `:spawn-failed` when startup failed.
Native startup failures, including missing executables and invalid directories, reach the callback with `:spawn-failed`.
Native startup can complete before the callback is dispatched; the child may already have exited when the callback runs.

### Stdio and backpressure

Stdin is a writable `portable-streams` endpoint. Stdout and stderr are independent readable endpoints.
The [streams contract](../portable-streams/README.md) supplies byte vectors, chunk bounds, operation slots, EOF, and stream-error handling.
Reads return nonempty vectors or nil at EOF. There is no automatic text or line decoding.
Each write accepts at most 1048576 bytes. One read per output and one write or finish on stdin may be pending.
Adapters read bounded chunks and wait for native write acceptance; they do not collect complete output internally.
Native pipe buffers add implementation-dependent memory beyond the portable read and write chunks.

Use `streams/finish!` on stdin to finish preceding writes and deliver EOF to the child.
`streams/pipe!` finishes stdin by default when its source reaches EOF.
Closing an individual stdio endpoint closes only that pipe. It does not request process termination.
Closing stdin can cause a program to exit; closing a readable pipe can cause native broken-pipe errors in its writer.
After process exit, stdout and stderr remain available for draining. Exit notification does not imply stdio EOF or output consumption.
Calling `terminate!` does not discard output. Calling `process/close!` closes all endpoints and discards unread output.

JVM native pipe closure can wait behind blocked I/O. Logical stream closure settles pending callbacks promptly; native cleanup uses daemon workers.
Native I/O already in progress can finish after closure. Its result is ignored once the stream callback has settled.
Use process termination to stop the child itself; cancelling a stream transfer only aborts that transfer's endpoints.

### Exit waiting and termination

Multiple independent waits can be pending, including while startup is in progress.
Each wait returns its own cancellation function. Cancelling it reports `:cancelled` to that wait without affecting other waits or the child.
An exit or startup failure that already committed wins over later cancellation.
Exit results are cached and available to later waits, including after `process/close!`.
Waits have no automatic timeout. The caller can cancel a wait or terminate the process according to its own deadline policy.

`:exit-code` is the native exit code when available. `:signal` is native signal metadata when available.
Normal exits report an integer code and nil signal on all verified runtimes.
Node reports nil code and a signal name when a signal ends the child.
JVM and Babashka expose the native integer exit value and nil signal; signal information is not inferred from numeric exit codes.
An exit code of zero identifies normal success across these result shapes.

`terminate!` accepts `{:force? false}` by default. JVM requests native destruction; Node requests SIGTERM.
With `:force? true`, JVM requests forced destruction and Node requests SIGKILL.
Termination semantics follow the OS. A normal request need not stop a program, and Windows may force termination for normal requests.
The callback confirms the request, not exit. Use `wait!` to confirm the process ended.
Requests made during startup wait for native creation. Requests after exit succeed without changing the cached result.
Native termination failures reach the callback with `:termination-failed`.

`close!` is idempotent, closes all stdio, and requests forced termination of a live or late-starting child.
Closing while startup is pending reports `:closed` to the startup callback if startup has not already committed.
Any late child is force-terminated and reaped. A committed startup success remains delivered if close races with callback dispatch.
Active waits still settle with the actual exit result or startup failure. Close is best effort; it provides no termination-error callback.
Process handles must be cleaned up explicitly. Garbage collection and JVM daemon workers do not provide child-process lifetime management.

## Runtime limits and errors

JVM and Babashka use `ProcessBuilder`, daemon I/O workers, and a native exit waiter.
Node uses `node:child_process` with paused output reads, write callbacks, and exit events.
The Node adapter requires ClojureScript's CommonJS `:nodejs` target.
Browsers throw `:portable/unsupported-operation` synchronously, including when process or require shims exist.
Options and callbacks are validated before runtime selection; handles are validated afterward.

Only the direct child is managed. Process groups, descendant termination, detached processes, PTYs, stdio redirection, and shell convenience APIs are outside this contract.
A descendant can keep an inherited output pipe open after the direct child exits. Native stream behavior can differ in that case.
The adapters use [JVM process APIs](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/Process.html)
and [Node child processes](https://nodejs.org/api/child_process.html).

| `:type` | Delivery | Reasons |
| --- | --- | --- |
| `:portable/invalid-input` | Synchronous throw | `:expected-map`, `:unknown-option`, `:expected-callback`, `:invalid-command`, `:invalid-cwd`, `:invalid-env`, `:expected-boolean`, `:invalid-process` |
| `:portable/unsupported-operation` | Synchronous throw | Qualified public `:operation`; no native process API in browsers |
| `:portable/process-error` | Callback, or synchronous stdio-access error | `:spawn-failed`, `:not-started`, `:closed`, `:cancelled`, `:termination-failed` |

Error data includes the qualified `:operation` and `:reason`; input errors also identify `:argument`.
Structured data excludes arguments and environment values. Retained native causes can include command or path details.
Stdio operations report `:portable/stream-error` through their stream callbacks. Native pipe failures can report `:io-error` or `:closed`.

## Verification

```sh
clojure -T:build test :modules :portable-process
clojure -T:build jar :modules :portable-process
```

Shared contracts spawn controlled Node fixtures on JVM, Babashka, and Node.
They cover binary echo over 1 MiB, simultaneous stdout/stderr draining, literal arguments, cwd, and environment overrides.
They also verify exit caching, wait cancellation, startup failures, termination during startup, forced termination, and pending stdio closure.
Browser tests verify unsupported operations and shared lifecycle transitions. Fixtures bound their own lifetime and are excluded from library jars.
Node is required for native test fixtures. The build task supplies inherited environment values for the environment contract.

Verification uses macOS, Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, ClojureScript 1.11.132, Node.js 24.22.1, and Chrome 157.0.8103.80.
Linux, Windows, Firefox, Safari, browser workers, ClojureCLR, ClojureDart, and jank remain unverified.
