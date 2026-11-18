# portable-http

`lambdaseq/portable-http` provides asynchronous HTTP requests through `portable.http`.
JVM Clojure, Babashka, and Node use portable's HTTP/1.1 codec over `portable-tcp` and `portable-tls`.
Browsers use Fetch. The `lambdaseq/portable` umbrella includes this library.

```clojure
(require '[portable.http :as http]
         '[portable.bytes :as bytes])

(def cancel!
  (http/request! {:url "https://example.com/api"
                  :method :post
                  :headers {"content-type" "text/plain; charset=utf-8"}
                  :body (bytes/encode-utf8 "λ")
                  :timeout-ms 5000}
    (fn [error response]
      (if error
        (println (ex-data error))
        (println (:status response) (:body response))))))

;; Call (cancel!) to cancel pending work. Repeating it is safe.
```

## Request and response contract

`request!` takes an options map and an error-first callback `(error response)`.
It returns an idempotent cancellation function. Callbacks are asynchronous and complete once.
JVM callbacks can run concurrently on daemon threads, including before the initiating function returns.
Malformed input and unavailable browser controls throw synchronously before starting the request.
Network, protocol, deadline, and cancellation failures reach the callback with nil as the response.

| Option | Contract |
| --- | --- |
| `:url` | Required absolute HTTP or HTTPS URL |
| `:method` | `:get` by default; also `:head`, `:post`, `:put`, `:patch`, `:delete`, `:options` |
| `:headers` | String-to-string map, default empty |
| `:body` | Unsigned byte vector, default empty; maximum 1048576 bytes |
| `:timeout-ms` | Whole-request deadline, default 10000; integer from 1 through 2147483647 |
| `:max-body-bytes` | Maximum returned body, default 1048576; integer from 0 through 16777216 |
| `:max-encoded-body-bytes` | Native encoded-body limit, default `min(16777216, max-body-bytes + 65536)`; integer from 0 through 16777216; unavailable in browsers |
| `:ca-certs` | Optional HTTPS trust anchors following the TLS PEM contract; unavailable in browsers |

GET and HEAD requests reject nonempty bodies. Unknown options are rejected.
URLs must be ASCII, at most 8192 characters, with valid percent escapes and no embedded credentials or backslashes.
Percent-encode non-ASCII URL text. Fragments are ignored.
Dot segments, including encoded dots, are rejected because Fetch would normalize them before transmission.

Request header names are case-insensitive ASCII tokens. Values are ASCII, with optional horizontal tabs; outer spaces and tabs are trimmed.
Duplicate names after case folding are rejected.
Host, Content-Length, Transfer-Encoding, Connection, Expect, Upgrade, TE, Trailer, Accept-Encoding, and Proxy-Connection are managed.
Callers cannot set managed headers. Request headers and start lines together are limited to 65536 bytes.

Success returns:

```clojure
{:status 200
 :headers {"content-type" ["application/octet-stream"]}
 :body [0 255 206 187 0]}
```

Header names are lowercase and values are vectors of strings.
Bodies contain application bytes after supported transfer and content decoding. There is no automatic text or JSON conversion.
Responses are buffered up to the configured body limit. Streaming callbacks are outside this contract.
HTTP 4xx and 5xx statuses are ordinary successful responses.
Redirect statuses with Location are rejected; redirects are never followed.
Cancellation stops local work but does not undo a request already sent. Requests are not retried automatically.

## Native HTTP/1.1 stack

Process runtimes encode requests and incrementally decode responses in shared `.cljc` code.
Each request owns one connection, which closes after completion. There is no pooling, pipelining, proxy support, or cookie jar.
HTTPS uses mandatory certificate and peer-name verification, with ALPN restricted to HTTP/1.1.
The [TLS contract](../portable-tls/README.md) applies to HTTPS connections and custom trust anchors.

Responses support HTTP/1.0 and HTTP/1.1, fixed lengths, chunked bodies, and connection-close framing.
HEAD, 204, and 304 responses have empty bodies. Informational responses are consumed before the final response.
Duplicate Content-Length fields and combined Content-Length/Transfer-Encoding are rejected.
Malformed headers, folded fields, unsupported upgrades, and truncated framed bodies fail explicitly.
Trailers are validated and discarded. Chunk extensions are treated as bounded opaque text.
Header blocks and trailers each have a 65536-byte limit and a 100-field limit.
Chunk-size lines have an 8192-byte limit. At most eight informational responses are accepted.

Native requests send Accept-Encoding: gzip, deflate. Responses decode through [portable-compression](../portable-compression/README.md).
Native decoding accepts identity, gzip, and zlib-wrapped deflate, with at most four listed content codings.
Stacked codings decode in reverse order. Each gzip layer accepts one member; zlib dictionaries and trailing data are rejected.
Other codings fail with `:unsupported-content-encoding`. Malformed compressed streams fail with `:invalid-compressed-body`.
The returned body limit applies after decoding. Encoded bytes and intermediate decoded layers use `:max-encoded-body-bytes`.
Identity bodies must fit both limits. Size failures use `:body-too-large`.
The request deadline and cancellation cover decompression; bounded native work can finish after cancellation without delivering another callback.
Content-Encoding and Content-Length remain received metadata and can describe encoded bytes rather than the returned body.
Additional transfer codings, CONNECT tunnels, protocol upgrades, and HTTP/2 or HTTP/3 are outside the native contract.

## Browser Fetch limits

Browsers use Fetch instead of raw TCP sockets. Node's `:nodejs` target always uses the portable protocol stack, even when Fetch exists.
Fetch controls wire protocol version, TLS trust, CORS, service workers, and network policy.
The wrapper requests omitted credentials, no-store caching, no referrer, and redirect rejection.
Browser-managed request headers fail explicitly when detectable, including Cookie, Origin, Referer, and restricted method-override headers.
Custom CA bundles throw `:custom-trust-unavailable`; the wrapper cannot bypass browser certificate checks.

Returned headers are limited to what Fetch exposes. Set-Cookie is hidden, and CORS can hide other headers.
Fetch can combine repeated header values into one string, which occupies one vector entry.
Framing checks apply to exposed headers; raw wire validation belongs to the browser.
Fetch transparently decompresses supported content encodings. The body limit applies to the decoded bytes.
An explicit `:max-encoded-body-bytes` throws `:encoded-limit-unavailable`; Fetch hides encoded bytes.
Fetch also controls compressed-stream validation. Chrome can accept corrupt checksums or incomplete gzip footers that native HTTP rejects.
Use standalone `portable.compression/decompress!` when validating an encoded byte vector; Fetch has already decoded HTTP bodies.
Browser network errors can combine CORS, TLS, DNS, or redirect failures without exposing their exact cause.

## Errors and verification

Input errors throw `ex-info` with `:type :portable/invalid-input`, `:operation :portable.http/request!`, and `:reason`.
Unavailable controls throw `:type :portable/unsupported-operation`, with reasons such as `:unsupported-header` or `:custom-trust-unavailable`.
Callback failures use `:type :portable/http-error` with the same operation and a reason.
Reasons include `:timeout`, `:cancelled`, `:network-error`, `:body-too-large`, `:ambiguous-framing`, `:incomplete-response`, `:invalid-compressed-body`, and `:redirect-blocked`.
TLS and transport reasons can also propagate. Browser errors often use `:network-error` instead of a more specific native reason.
Native causes are retained; error data excludes request headers and bodies.

```sh
clojure -T:build test :modules :portable-http
clojure -T:build jar :modules :portable-http
```

Shared contracts run the pure codec on every runtime and exercise real loopback HTTP requests, including Chrome Fetch.
Compressed cases cover both formats, stacked decoding, fixed/chunked/close framing, empty output, malformed data, and separate size bounds.
Native HTTPS contracts reuse TLS test credentials and verify trusted success and untrusted rejection.
The browser test page is served over loopback HTTP, with the fixture server stopped after verification.
Fixture servers use native HTTP implementations; production process-runtime clients do not.
Test fixtures are excluded from library jars. Node and local loopback permission are required for the shared suite.

Verification uses macOS, Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, ClojureScript 1.11.132, Node.js 24.22.1, and Chrome 157.0.8103.80.
Linux, Windows, IPv6 endpoints, Firefox, Safari, ClojureCLR, ClojureDart, and jank remain unverified.
