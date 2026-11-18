# portable-udp

`lambdaseq/portable-udp` provides asynchronous IPv4 unicast datagrams through `portable.udp`.
JVM Clojure, Babashka, and Node use native UDP sockets. Browser operations fail explicitly.
The library depends on `portable-bytes`. The `lambdaseq/portable` umbrella includes it.

```clojure
(require '[portable.udp :as udp])

;; Bind an ephemeral loopback port, then send a packet to that socket.
(def socket
  (udp/bind! {}
    (fn [error {:keys [socket port]}]
      (if error
        (println (ex-data error))
        (udp/send! socket [0 255 128]
          {:host "127.0.0.1" :port port}
          (fn [error _]
            (if error
              (do (println (ex-data error)) (udp/close! socket))
              (udp/receive! socket
                (fn [error packet]
                  (println (if error (ex-data error) packet))
                  (udp/close! socket))))))))))
```

## Socket and callback contract

| Function | Arguments | Callback success value | Immediate return |
| --- | --- | --- | --- |
| `bind!` | Options, callback | `{:socket socket :host address :port port}` | Opaque socket |
| `send!` | Socket, byte vector, destination options, callback | Accepted byte count | nil |
| `receive!` | Socket, optional options, callback | `{:data bytes :host sender-address :port sender-port}` | nil |
| `close!` | Socket | No callback | nil |

Callbacks take `(error value)`. Success supplies nil as the error; failure supplies nil as the value.
Each operation settles once, asynchronously. JVM callbacks can run concurrently, including before the initiating function returns.
Treat sockets as opaque resources. Payloads and address metadata are ordinary portable values.
Malformed arguments throw synchronously before native I/O. Unknown options are rejected.

`bind!` defaults to `{:host "127.0.0.1" :port 0}`.
Port zero asks the operating system for an available port; the callback returns the selected port.
Use `:host "0.0.0.0"` explicitly to listen on all IPv4 interfaces.
Explicit bind ports range from 0 through 65535. Send ports range from 1 through 65535.
Each socket supports one pending send and one pending receive concurrently.
Overlapping operations in the same direction report `:busy` without cancelling the first operation.
Operations started before binding completes report `:not-bound`.

Addresses must be canonical dotted-decimal IPv4 literals, without leading zeros.
Hostnames, IPv6, multicast addresses, and the limited broadcast address are rejected.
The wildcard address is accepted only for binding. Native broadcast transmission is disabled.
Connected UDP, multicast membership, broadcast controls, and socket buffer tuning are outside this contract.

## Datagram and deadline limits

Payloads are immutable unsigned byte vectors, as defined by [portable-bytes](../portable-bytes/README.md).
Empty vectors represent valid empty datagrams. UDP has no EOF marker.
Each send transmits one datagram; each successful receive returns one complete datagram.
The payload ceiling is 65507 bytes. Operating systems and network paths can impose smaller limits.
Send completion means local acceptance; it does not confirm peer receipt.
Delivery, ordering, and protection against duplicates are outside UDP's guarantees.

`receive!` accepts `:max-bytes`, from 0 through 65507, defaulting to 65507.
An oversized datagram is consumed and reports `:datagram-too-large` without exposing truncated bytes.
The socket remains usable. A zero limit accepts only empty packets.

Bind, send, and receive options accept `:timeout-ms`, defaulting to 10000.
It must be an integer from 1 through 2147483647. Deadlines begin when the operation is reserved.
A deadline closes the socket and settles all pending operations with `:timeout` errors.
Native I/O failures also close the socket. There are no automatic retries.
Closing repeatedly is safe. Pending and subsequent operations report `:closed`.
Closing cannot undo a packet already sent.

Operating system receive buffers can drop packets under load.
Node also queues at most 64 datagrams and 1048576 payload bytes before application receives consume them.
Packets exceeding either queue bound are dropped whole; queued packets retain their order.

## Errors and runtime support

Input errors use `:type :portable/invalid-input`, the qualified operation, and a reason.
Callback errors use `:type :portable/udp-error`, the qualified operation, and a reason.
Reasons include `:bind-failed`, `:io-error`, `:timeout`, `:closed`, `:busy`, `:not-bound`, and `:datagram-too-large`.
Native causes are retained when available. Error data excludes payloads.

Browsers synchronously throw `ex-info` with `:type :portable/unsupported-operation` and the qualified operation.
This applies to all four functions, including `close!`. Input validation precedes runtime checks.
The Node adapter requires ClojureScript's `:nodejs` target with CommonJS `require`.
Browser workers do not gain socket access through this adapter.

```sh
clojure -T:build test :modules :portable-udp
clojure -T:build jar :modules :portable-udp
```

Shared contracts use independent native echo fixtures on JVM, Babashka, and Node.
Chrome runs the shared input, lifecycle, queue-bound, and unsupported-operation contracts.
Fixtures and test runners are excluded from library jars. Native contracts require local loopback permission.
Verification uses macOS, Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, ClojureScript 1.11.132, Node.js 24.22.1, and Chrome 157.0.8103.80.
Linux, Windows, Firefox, Safari, ClojureCLR, ClojureDart, and jank remain unverified.
