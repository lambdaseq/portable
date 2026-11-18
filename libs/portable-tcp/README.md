# portable-tcp

`lambdaseq/portable-tcp` provides asynchronous TCP connections and IPv4 listeners through `portable.tcp`.
The `lambdaseq/portable` umbrella also includes it.
JVM Clojure, Babashka, and Node use native sockets. Browsers reject every operation explicitly.

```clojure
(require '[portable.tcp :as tcp])

;; Requires a TCP service at this address.
(def connection
  (tcp/connect! {:host "127.0.0.1" :port 9000 :timeout-ms 2000}
    (fn [error connected]
      (if error
        (println (ex-data error))
        (tcp/write! connected [104 105]
          (fn [write-error accepted]
            (println write-error accepted)
            (tcp/close! connected)))))))
```

## Callback contract

Callbacks receive two arguments: `(error value)`.
Success supplies nil as the error. Failure supplies an `ex-info` error and nil as the value.
Callbacks are dispatched asynchronously, never called inline by an operation.
JVM callbacks can run on another thread before the initiating function returns.
Each accepted operation completes once, including after timeout or cancellation.
Callbacks for concurrent operations can run concurrently on JVM runtimes.

| Function | Immediate return | Successful callback value |
| --- | --- | --- |
| `(listen! options callback)` | Opaque listener handle | Bound listener handle |
| `(accept! listener callback)` | nil | Connected handle |
| `(local-address listener)` | Bound `{:host string :port integer}` | No callback |
| `(connect! options callback)` | Opaque connection handle | Connected handle |
| `(read! connection callback)` | nil | Nonempty unsigned byte vector, or nil at EOF |
| `(write! connection bytes callback)` | nil | Number of bytes accepted locally |
| `(shutdown-output! connection callback)` | nil | nil |
| `(close! connection-or-listener)` | nil | No callback |

Accept, read, write, and output shutdown accept an optional options map immediately before the callback.
Handles represent live resources. Do not inspect, alter, copy, or serialize their contents.
The byte representation comes from [portable-bytes](../portable-bytes/README.md).

### Options and limits

Connect requires a nonblank string `:host` without NUL and an integer `:port` from 1 through 65535.
Every asynchronous operation accepts `:timeout-ms`, defaulting to 10000.
Timeouts must be integer milliseconds from 1 through 2147483647. Zero does not disable deadlines.
Connect deadlines include hostname resolution from the caller's perspective; native DNS work may continue after cancellation.

Reads accept `:max-bytes`, defaulting to 65536, from 1 through 1048576.
Writes accept unsigned byte vectors up to 1048576 bytes. Empty writes succeed with a count of zero.
Unknown option keys are rejected.

Only one read and one write may be pending per connection. Output shutdown shares the write slot.
Wait for a write callback before submitting another write. This bounds application queuing and supplies backpressure.
Read and write operations can proceed concurrently.
TCP has no message boundaries: one write can require several reads, and a read can combine multiple writes.
Write completion indicates local acceptance, not receipt by the peer. Writes are not retried automatically.

### Lifecycle

Use a successful connect or accept callback before reading or writing.
The immediately returned connect handle allows cancellation through `close!` while connection setup is pending.
When cancellation races with completion, the first committed outcome wins.

`shutdown-output!` sends EOF after completed writes and leaves input available.
Further writes and repeated output shutdown fail with `:output-closed`.
Remote EOF leaves the output direction available. Repeated reads after EOF return nil until explicitly closed.
`close!` aborts both directions, cancels pending work, and is safe to repeat.
Any operation timeout or native I/O failure closes the connection and settles all pending operations.
Closing after an operation completes does not replace its committed result.

## Listening and accepting

```clojure
(def listener
  (tcp/listen! {:host "127.0.0.1" :port 0}
    (fn [error listening]
      (if error
        (println (ex-data error))
        (do
          (println (tcp/local-address listening))
          (tcp/accept! listening
            (fn [error connection]
              (tcp/close! listening)
              (if error
                (println (ex-data error))
                (tcp/write! connection [104 105]
                  (fn [error _]
                    (println error)
                    (tcp/close! connection)))))))))))
```

`listen!` binds a numeric IPv4 address. Defaults are `:host "127.0.0.1"`, `:port 0`, `:backlog 128`, and `:timeout-ms 10000`.
Port 0 selects an ephemeral port. Ports range from 0 through 65535; backlog ranges from 1 through 1024.
Use `"0.0.0.0"` to bind all IPv4 interfaces. Hostnames, IPv6 binds, and port reuse controls are outside the listener contract.
The OS may clamp the requested backlog. Node additionally bounds its queue of undelivered accepted sockets to that backlog.
Excess Node connections are closed. Do not rely on an exact queue capacity or arrival order across runtimes.

Wait for the successful listen callback before accepting or obtaining the bound address.
`local-address` returns the numeric bound host and actual port. The address remains available after a bound listener closes.
Before binding, it throws `:portable/tcp-error` with `:reason :not-listening`.
Listeners and connections are distinct handle types; connection I/O rejects listener handles.

Each `accept!` delivers one ordinary connection that supports read, write, output shutdown, close, and TCP ownership transfer.
Only one accept may be pending per listener; concurrent attempts fail asynchronously with `:busy`.
Accept before binding fails asynchronously with `:not-connected`; accept after close fails with `:closed`.
Closing a listener cancels pending listen/accept callbacks and releases undelivered connections.
Delivered connections remain owned by the caller and must be closed separately.
Listen or accept deadlines close the listener. A late native accept is closed if its callback can no longer receive it.
Committed accept results remain delivered when close races with callback dispatch.

## Validation, errors, and browsers

Malformed options, callbacks, byte vectors, and connection handles throw `ex-info` synchronously.
These errors carry `:type :portable/invalid-input`, `:operation`, and `:reason`.
Reasons include `:expected-options-map`, `:unknown-option`, `:expected-callback`, `:invalid-host`, `:invalid-port`,
`:invalid-timeout`, `:invalid-backlog`, `:invalid-listener`, `:invalid-read-size`, `:expected-byte-vector`, `:write-too-large`, and `:invalid-connection`.

Valid calls on unsupported runtimes throw synchronously with `:type :portable/unsupported-operation` and `:operation`.
Options, callbacks, and payloads are validated before host selection; handles are validated afterward.
Browser tests therefore do not require fabricated socket handles.

Connection and I/O failures reach callbacks with `:type :portable/tcp-error`, `:operation`, and `:reason`.
Reasons include `:timeout`, `:closed`, `:not-connected`, `:busy`, `:output-closed`, `:connection-refused`, `:address-in-use`, `:address-unavailable`, `:dns-failure`, and `:connection-reset`.
Native failures that lack a recognized category use `:io-error`. The original error is retained as the cause.
Error data excludes hostnames and payloads; native causes can contain host details.

## Runtimes and verification

JVM and Babashka use daemon threads per operation and a shared daemon deadline scheduler.
Node uses `node:net` events, paused reads, and write completion callbacks.
The Node adapter requires the ClojureScript `:nodejs` target and CommonJS `require`.
Browsers and other JavaScript targets are unsupported, including with process or require shims.
TCP provides client connections and listeners. TLS handshakes, HTTP servers, and UDP remain separate concerns.

From the workspace root:

```sh
clojure -T:build test :modules :portable-tcp
clojure -T:build jar :modules :portable-tcp
```

Shared contracts use loopback-only native servers with ephemeral ports and deterministic byte payloads.
They check fragmented reads, half-close, repeated EOF, cancellation, refused connections, deadlines, and callback completion.
Listener contracts cover ephemeral ports, queued clients, repeated accepts, bind failures, busy accepts, listener cancellation, and connection ownership.
Browser contracts check unsupported operations, validation, and the shared lifecycle state machine.
Read counts vary because TCP chunk boundaries vary.
The [browser runner](../portable-clock/README.md#browser-runner) also applies to this library.

Verification uses macOS, Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, and ClojureScript 1.11.132.
JavaScript verification uses Node.js 24.22.1 and Chrome 157.0.8103.80.
Build prerequisites match the other families. Tests require local loopback networking permission.
Linux, Windows, Firefox, Safari, ClojureCLR, ClojureDart, and jank remain unverified.
