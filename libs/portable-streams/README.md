# portable-streams

`lambdaseq/portable-streams` provides asynchronous byte streams, bounded memory pipes, and cancellable transfers through `portable.streams`.
The core depends only on `portable-bytes`. The `lambdaseq/portable` umbrella includes it.
JVM Clojure, Babashka, Node, and browsers use the same implementation and callback contract.

```clojure
(require '[portable.streams :as streams]
         '[portable.bytes :as bytes])

(def cancel!
  (let [input (streams/open-source! (bytes/encode-utf8 "hello λ"))
        {:keys [source sink]} (streams/open-pipe! {:capacity 4})]
    (letfn [(consume! []
              (streams/read! source
                (fn [error chunk]
                  (cond
                    error (println (ex-data error))
                    chunk (do (println (pr-str chunk)) (consume!))
                    :else (do (println "EOF") (streams/close! source))))))]
      (consume!)
      (streams/pipe! input sink {:chunk-bytes 8}
        (fn [error copied]
          (println (if error (ex-data error) copied)))))))

;; Call (cancel!) to abort a pending transfer. Repeating it is safe.
```

Chunks contain unsigned bytes. UTF-8 characters can span chunks; a chunk is not necessarily a complete text value.
A bounded destination must be consumed while a larger transfer writes to it.
JVM scripts must keep their process alive until callbacks finish.

## Stream contract

| Function | Immediate result | Callback result on success |
| --- | --- | --- |
| `(open-source! bytes)` | Readable handle over a finite immutable byte vector | No callback |
| `(open-pipe! options)` | `{:source readable-handle :sink writable-handle}` | No callback |
| `(read! source options callback)` | nil | Nonempty byte vector, or nil at EOF |
| `(write! sink bytes callback)` | nil | Accepted byte count |
| `(finish! sink callback)` | nil | nil |
| `(close! endpoint)` | nil | No callback |
| `(pipe! source sink options callback)` | Idempotent cancellation function | Copied byte count |

Options are optional for `open-pipe!`, `read!`, and `pipe!`.
Callbacks receive `(error result)` once, asynchronously. Failure supplies an error and nil as the result.
Input validation throws synchronously before taking ownership or scheduling work.
Callback exceptions escape provider error handling and do not trigger another callback.
JVM callbacks can run concurrently on daemon threads, including before the initiating function returns.
They retain the initiating operation's dynamic bindings, including when another endpoint or canceller triggers completion.
JavaScript callbacks run through the event loop. Piping periodically yields so timers can request cancellation during continuous transfers.

Handles represent live readable or writable endpoints. Do not inspect, alter, copy, or serialize their contents.
Read and write handles are distinct; using the wrong direction throws `:wrong-direction`.
The [portable-bytes representation](../portable-bytes/README.md) applies to all input and output vectors.
There is no implicit text, Base64, or hex conversion.

### Bounds and backpressure

| Setting | Default | Allowed range |
| --- | --- | --- |
| `open-pipe! :capacity` | 65536 buffered bytes | Integer from 1 through 1048576 |
| `read! :max-bytes` | 65536 bytes | Integer from 1 through 1048576 |
| `pipe! :chunk-bytes` | 65536 bytes | Integer from 1 through 1048576 |
| `pipe! :max-bytes` | 9007199254740991 total copied bytes | Integer from 0 through 9007199254740991 |
| `pipe! :finish?` | true | Boolean |

`open-source!` accepts vectors up to 16777216 bytes (16 MiB). It retains its supplied vector until consumed or closed.
Each `write!` accepts at most 1048576 bytes (1 MiB), including empty vectors.
A stream can carry more than one write's limit through successive writes.
Chunks have no message boundaries: reads can split a write or combine bytes from successive writes.

An in-memory pipe buffers at most its capacity. A larger write waits for readers to free room.
One read may be pending per source. One write or finish may be pending per sink; other attempts fail asynchronously with `:busy`.
There is no unbounded write queue. Empty writes succeed with zero and do not signal EOF.
A pending write can retain its input vector in addition to the bounded buffer.
Read results, temporary copies, and callback work add memory beyond the byte-buffer capacity.

A write completes when all its bytes have been accepted. The reader can consume prefixes before the write callback completes.
Write completion confirms acceptance, not consumption. A failed write can have delivered a prefix; retries are not automatic.

### EOF, finish, and close

`finish!` gracefully ends output. Wait for the preceding write or finish callback before calling it.
Buffered bytes remain readable, followed by nil at EOF. EOF is never an empty vector.
Repeating finish after completion succeeds. Further writes, including empty writes, fail with `:output-closed`.
Repeated reads after EOF return nil until the source is closed.
Finish completion commits before a waiting reader is notified of EOF.

`close!` aborts a stream and discards unread buffered bytes.
Closing either endpoint of a memory pipe closes both ends, including idle endpoints and cached EOF states.
Pending operations and further reads, writes, or finishes fail with `:closed`.
Closing again is safe. A committed operation can still deliver its queued callback after close; close does not replace that result.
Use finish to preserve buffered output. Use close to cancel work and release ownership of buffered data.

## Piping and ownership

`pipe!` copies between independent streams. Connecting a memory pipe's source back to its own sink is rejected with `:same-stream`.
The transfer exclusively owns the source's read side and the destination's write side until completion.
The source producer can still write, and the destination consumer can still read.
Competing reads from the owned source, or writes and finishes on the owned sink, fail with `:busy`.

A transfer reads one bounded chunk, waits for that chunk's write completion, and only then reads again.
This backpressure bounds transfer memory even when a consumer is slow. It does not retain the complete stream.
If either side is busy, closed, or already finished on the output side, transfer startup fails asynchronously.
A failed ownership claim leaves existing operations and other streams unchanged.

At EOF, piping finishes the sink by default and releases both endpoint claims.
The source remains readable at EOF. The destination's buffered bytes still require consumption.
With `:finish? false`, piping releases the destination for additional writes; the caller decides when to finish it.
A successful callback reports accepted bytes, not confirmation that the destination consumer has drained them.
Cancelling after completion is a no-op.

The total byte ceiling is separate from the chunk and buffer bounds.
An exact-limit source succeeds after EOF is confirmed. At the limit, piping probes for one additional byte without writing it.
Extra input fails with `:byte-limit-exceeded`. A zero ceiling permits empty input.
Failure or cancellation aborts both owned endpoints, including their memory-pipe peers, and returns no partial result.
Bytes already consumed downstream cannot be undone. Closing an owned endpoint also ends the transfer.
Queued work may finish after cancellation; its result is ignored.

The core provides finite memory sources and bounded memory pipes on every verified runtime.
It exposes a private adapter boundary for other libraries without depending on TCP, TLS, filesystem, HTTP, or compression.
Operation deadlines and automatic retries are outside this contract; close endpoints or cancel transfers to stop waiting work.

## Errors and verification

Errors use `ex-info` with the qualified `:operation` and `:reason`.
Input errors also identify `:argument`. Structured error data excludes byte vectors; provider causes are retained.

| `:type` | Delivery | Reasons |
| --- | --- | --- |
| `:portable/invalid-input` | Synchronous throw | `:expected-map`, `:unknown-option`, `:out-of-range`, `:expected-boolean`, `:expected-callback`, `:expected-byte-vector`, `:input-too-large`, `:invalid-stream`, `:wrong-direction`, `:same-stream` |
| `:portable/stream-error` | Callback | `:busy`, `:closed`, `:output-closed`, `:cancelled`, `:byte-limit-exceeded`, `:io-error`, `:invalid-result` |

```sh
clojure -T:build test :modules :portable-streams
clojure -T:build jar :modules :portable-streams
```

Shared contracts cover binary and empty sources, fragmented reads, bounded writes, EOF, graceful finish, and closure propagation.
Piping tests cover buffer pressure, exclusive ownership, exact and excessive byte limits, concurrent transfers, and transfers larger than one write.
They verify blocked-write cancellation, late provider completion, timer-driven cancellation, and callback exceptions.
Adapter checks reject empty or oversized reads and incorrect write/finish results. JVM checks also verify dynamic binding conveyance.
Tests run in actual headless Chrome and are excluded from library jars.

Verification uses macOS, Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, ClojureScript 1.11.132, Node.js 24.22.1, and Chrome 157.0.8103.80.
Linux, Windows, Firefox, Safari, browser workers, ClojureCLR, ClojureDart, and jank remain unverified.
