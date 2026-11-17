# portable-bytes

`lambdaseq/portable-bytes` provides UTF-8, Base64, and hex conversion through `portable.bytes`.
The `lambdaseq/portable` umbrella also includes it.

```clojure
(require '[portable.bytes :as bytes])

(bytes/encode-utf8 "λ")       ; => [206 187]
(bytes/decode-utf8 [206 187])  ; => "λ"
(bytes/encode-base64 [0 255]) ; => "AP8="
(bytes/decode-base64 "AP8=")  ; => [0 255]
(bytes/encode-hex [0 255])    ; => "00ff"
(bytes/decode-hex "00FF")     ; => [0 255]
```

## Contract

Bytes are immutable vectors of integers from `0` through `255`.
Native arrays, lists, nil, and out-of-range values are rejected.
All conversions are pure and synchronous. No function changes its input.

| Function | Input | Output |
| --- | --- | --- |
| `encode-utf8` | Unicode string | Byte vector |
| `decode-utf8` | Byte vector | Unicode string |
| `encode-base64` | Byte vector | Standard padded Base64 string |
| `decode-base64` | Canonical padded Base64 string | Byte vector |
| `encode-hex` | Byte vector | Lowercase hex string |
| `decode-hex` | Even-length hex string, either case | Byte vector |

Empty strings and vectors convert to their empty counterpart.
UTF-8 preserves BOMs, NULs, and Unicode composition. It rejects malformed byte sequences and unpaired UTF-16 surrogates.
Base64 follows [RFC 4648](https://www.rfc-editor.org/rfc/rfc4648).
It rejects whitespace, URL-safe characters, missing required padding, and nonzero unused padding bits.
Hex rejects whitespace, prefixes such as `0x`, and odd lengths.

Invalid input throws `ex-info` with `:type :portable/invalid-input`, `:operation`, and `:reason`.
Operations are namespaced keywords such as `:portable.bytes/decode-utf8`.
Error data excludes the input value.

| Reason | Meaning |
| --- | --- |
| `:expected-string` | The operation requires a string |
| `:expected-byte-vector` | The operation requires a vector of unsigned bytes |
| `:invalid-unicode` | The string contains an unpaired surrogate |
| `:invalid-utf8` | Bytes are not valid UTF-8 |
| `:invalid-base64` | Text is not canonical padded Base64 |
| `:invalid-hex` | Text is not even-length hex |

## Runtimes

JVM and Babashka use Java UTF-8 codecs and Base64.
JavaScript uses `TextEncoder`, fatal `TextDecoder`, and Closure's Base64 codec.
Hex conversion is shared Clojure code.
Missing JavaScript text codecs throw `ex-info` with `:type :portable/unsupported-operation` and `:operation`.

Shared contracts run on Clojure 1.12.4, Babashka 1.13.219, and ClojureScript 1.11.132 in Node.js 24.22.1 and Chrome 157.0.8103.78.
The JVM build requires JDK 17+; verification uses JDK 25.0.3.
Node.js 18+ supplies the required JavaScript globals.
Firefox, Safari, ClojureCLR, ClojureDart, and jank are unverified.

## Verification

From the workspace root:

```sh
clojure -T:build test :modules :portable-bytes
clojure -T:build jar :modules :portable-bytes
```

Contracts include RFC vectors, Unicode examples, invalid input, every byte value, and large round trips.
The [browser runner](../portable-clock/README.md#browser-runner) also applies to this library.
Set `CHROME_BIN` if Chrome or Chromium is outside the detected locations.
Browser results replace this library's `target/browser-results.html` and `target/browser-stderr.log`.
Test support and the ClojureScript compiler are development dependencies only.
