# portable-compression

`lambdaseq/portable-compression` provides bounded gzip and zlib-wrapped DEFLATE through `portable.compression`.
JVM Clojure, Babashka, Node, and browsers share byte-vector inputs, outputs, and asynchronous callbacks.
It depends on `portable-bytes`. The `lambdaseq/portable` umbrella includes it.

```clojure
(require '[portable.compression :as compression]
         '[portable.bytes :as bytes])

(compression/compress! :gzip (bytes/encode-utf8 "hello λ")
  (fn [error encoded]
    (if error
      (println (ex-data error))
      (compression/decompress! :gzip encoded {:max-output-bytes 1024}
        (fn [error decoded]
          (println (if error (ex-data error) (bytes/decode-utf8 decoded))))))))
```

## Contract

| Function | Arguments | Result |
| --- | --- | --- |
| `compress!` | Format, byte vector, optional options map, callback | Compressed byte vector |
| `decompress!` | Format, byte vector, optional options map, callback | Decompressed byte vector |

Formats are `:gzip` and `:deflate`. DEFLATE includes the zlib header and Adler-32 trailer; raw DEFLATE is outside this contract.
Inputs and outputs are immutable unsigned byte vectors, as defined by [portable-bytes](../portable-bytes/README.md).
Empty input can be compressed. Decompression requires a complete encoded stream, including for empty output.
Compressed bytes can differ between runtimes. Decoding produces the same original bytes.

Input vectors are limited to 16777216 bytes (16 MiB).
The sole option, `:max-output-bytes`, defaults to 1048576 (1 MiB).
It accepts integers from 0 through 16777216 and bounds either operation's output.
Decoding stops when output exceeds the limit; failures return no partial data.
A zero limit permits a valid stream with empty decoded output.
Implementations bound output collection while processing, before creating the returned vector.
Native buffers and vector copies add memory overhead beyond the configured byte count.

Decompression accepts one gzip member or one zlib stream.
It rejects trailing bytes, concatenated streams, truncated data, invalid headers, and checksum mismatches.
Gzip's CRC32 and uncompressed-size trailer are verified. Optional header fields and header CRC are supported.
Gzip headers are limited to 65536 bytes. Zlib preset dictionaries are rejected with `:dictionary-required`.
Compression levels, custom dictionaries, streaming APIs, cancellation, and deadlines are outside this contract.

Callbacks receive `(error result)` once, asynchronously. Calls return nil.
Success supplies nil as the error. Failure supplies nil as the result.
Invalid arguments throw synchronously before scheduling work.
Missing native APIs and operational failures reach the callback.
Caller callback exceptions escape native failure handling and do not trigger another callback.
JVM callbacks run on daemon workers and may run before the initiating function returns.
JVM scripts must keep their process alive until callbacks finish.

## Runtime adapters

| Runtime | Native implementation |
| --- | --- |
| JVM, Babashka | `java.util.zip.Deflater` and `Inflater`, with shared gzip framing and CRC checks |
| ClojureScript, Node | Asynchronous `node:zlib`, bounded with `maxOutputLength` and checked for complete input consumption |
| ClojureScript, browser | `CompressionStream` and `DecompressionStream`, with concurrent bounded stream reads |

Node requires ClojureScript's CommonJS `:nodejs` target. Browser constructors must support `gzip` and `deflate`.
Missing constructors or Node methods fail explicitly. Browser decompression validates streams independently of Fetch.
Formats follow [RFC 1950](https://www.rfc-editor.org/rfc/rfc1950.html) and [RFC 1952](https://www.rfc-editor.org/rfc/rfc1952.html).
Browser adapters use the [Compression Streams API](https://compression.spec.whatwg.org/).

## Errors and verification

Errors use `ex-info` with a qualified `:operation` and `:reason`.
Input errors identify `:argument`; structured error data excludes input and output bytes.

| `:type` | Delivery | Reasons |
| --- | --- | --- |
| `:portable/invalid-input` | Synchronous throw | `:unsupported-format`, `:expected-byte-vector`, `:input-too-large`, `:expected-map`, `:unknown-option`, `:out-of-range`, `:expected-callback` |
| `:portable/unsupported-operation` | Callback | `:compression-unavailable` |
| `:portable/compression-error` | Callback | `:invalid-data`, `:dictionary-required`, `:output-too-large`, `:compression-failure`, `:invalid-result` |

```sh
clojure -T:build test :modules :portable-compression
clojure -T:build jar :modules :portable-compression
```

Shared tests decode independent Python gzip/zlib fixtures and round-trip empty, binary, text, and larger payloads.
They cover stored, fixed, and dynamic DEFLATE blocks, optional gzip headers, malformed streams, checksums, and size limits.
Lifecycle tests cover asynchronous callbacks, concurrent engines, missing APIs, invalid results, and duplicate completion.
Browser tests use actual Chrome Compression Streams. Test fixtures are excluded from library jars.

Verification uses macOS, Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, ClojureScript 1.11.132, Node.js 24.22.1, and Chrome 157.0.8103.80.
Linux, Windows, Firefox, Safari, browser workers, ClojureCLR, ClojureDart, and jank remain unverified.
