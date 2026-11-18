# portable-crypto

`lambdaseq/portable-crypto` provides asynchronous digests and HMACs through `portable.crypto`.
JVM Clojure, Babashka, Node, and browsers use native cryptographic primitives behind the same callback contract.
The library depends on `portable-bytes`. The `lambdaseq/portable` umbrella includes it.

```clojure
(require '[portable.crypto :as crypto]
         '[portable.bytes :as bytes])

(crypto/digest! :sha-256 (bytes/encode-utf8 "abc")
  (fn [error result]
    (println (if error (ex-data error) (bytes/encode-hex result)))))

;; The caller supplies and manages the HMAC key bytes.
(defn authenticate! [key data]
  (crypto/hmac! :sha-512 key data
    (fn [error result]
      (println (if error (ex-data error) (bytes/encode-hex result))))))
```

## API contract

| Function | Arguments | Operation |
| --- | --- | --- |
| `digest!` | Algorithm, data, callback | SHA-256 or SHA-512 digest |
| `hmac!` | Algorithm, key, data, callback | HMAC using SHA-256 or SHA-512 |

Both functions accept `:sha-256` or `:sha-512` as the algorithm selector.
Inputs and outputs use immutable unsigned byte vectors, as defined by [portable-bytes](../portable-bytes/README.md).
Strings and native arrays require explicit conversion. There is no automatic text, hex, or Base64 conversion.
Messages may be empty and are limited to 1048576 bytes.
HMAC keys must contain from 1 through 1048576 bytes. Empty keys are rejected consistently across runtimes.
Keys longer than the hash block size follow native HMAC processing.

The callback receives `(error result)`. Success supplies nil as the error and a complete byte vector as the result.
Failures supply an error and nil as the result. SHA-256 operations return 32 bytes; SHA-512 operations return 64 bytes.
HMAC operations return the complete tag. Inputs remain unchanged, including when operations overlap.

Valid calls return nil and complete the callback once, asynchronously.
Callbacks run outside the initiating call stack. JVM callbacks can run before the initiating function returns.
Input validation is synchronous and throws before scheduling work.
Missing native APIs, provider failures, and malformed native results reach the callback.
Caller callback exceptions remain outside native error handling; they do not trigger another callback.
JVM scripts must keep the process alive until their callbacks finish.

Calls provide no cancellation handle or deadline. The API processes one complete byte vector per operation.
JVM and Babashka use daemon workers. Node queues native computation on its event loop, which that work can occupy.
Browsers use Web Crypto promises internally. Public values contain neither promises nor native key objects.

## Native sources and runtime limits

| Runtime | Digest | HMAC |
| --- | --- | --- |
| JVM, Babashka | `java.security.MessageDigest` | `javax.crypto.Mac` with `SecretKeySpec` |
| ClojureScript, Node | `node:crypto.createHash` | `node:crypto.createHmac` |
| ClojureScript, browser | `crypto.subtle.digest` | Non-extractable raw key import, then `crypto.subtle.sign` |

Each JVM and Node operation owns a fresh engine. Browser HMAC keys are imported only for signing and remain private.
The adapters use [JVM digests](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/security/MessageDigest.html),
[JVM MACs](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/javax/crypto/Mac.html),
[Node crypto](https://nodejs.org/docs/latest-v24.x/api/crypto.html), and [Web Crypto](https://www.w3.org/TR/webcrypto/).
Node requires ClojureScript's CommonJS `:nodejs` target.
Browsers require `globalThis.crypto.subtle`, normally available in a secure context.
Missing methods fail explicitly. A provider that rejects an allowed algorithm reports a native crypto failure.

## Errors and verification

Errors use `ex-info` with the qualified `:operation` and a `:reason`.

| `:type` | Delivery | Reasons |
| --- | --- | --- |
| `:portable/invalid-input` | Synchronous throw | `:unsupported-algorithm`, `:expected-byte-vector`, `:input-too-large`, `:empty-key`, `:expected-callback` |
| `:portable/unsupported-operation` | Callback | `:crypto-unavailable` |
| `:portable/crypto-error` | Callback | `:crypto-failure`, `:invalid-result` |

Input errors also identify `:argument` as `:algorithm`, `:data`, `:key`, or `:callback`.
Structured error data excludes keys, messages, and native results. Native failure causes are retained.

```sh
clojure -T:build test :modules :portable-crypto
clojure -T:build jar :modules :portable-crypto
```

Shared contracts exercise real native engines on JVM, Babashka, Node, and Chrome.
They include [standard SHA examples](https://www.rfc-editor.org/rfc/rfc6234.html),
[RFC 4231 HMAC vectors](https://www.rfc-editor.org/rfc/rfc4231.html), and independent binary and boundary reference values.
Cases cover padding boundaries, one million message bytes, the 1 MiB limit, and long keys.
Lifecycle checks cover concurrency, callback ordering, native failures, missing methods, and duplicate completion.
Browser checks also exercise promise rejection and non-extractable HMAC key import.
Tests are excluded from library jars. The library has no dependency on networking or random generation.

Verification uses macOS, Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, ClojureScript 1.11.132, Node.js 24.22.1, and Chrome 157.0.8103.80.
Linux, Windows, Firefox, Safari, browser workers, ClojureCLR, ClojureDart, and jank remain unverified.
