# portable-http

`lambdaseq/portable-http` provides asynchronous HTTP clients and connection serving through `portable.http`.
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

## Serving HTTP connections

`serve!` implements one HTTP exchange over an already connected TCP or TLS handle.
It parses the request, calls the application handler, writes the response, and closes the connection.
It returns an idempotent cancellation function. Its completion callback receives `(error nil)` exactly once.
Production parsing and serialization use portable's shared HTTP/1.1 stack; no host HTTP server is used.
See [HTTP/1.1 framing](https://www.rfc-editor.org/rfc/rfc9112.html) for the protocol and the serving limits below.

```clojure
(require '[portable.tcp :as tcp])

(defn handle! [request respond!]
  (respond! {:status 200
             :headers {"content-type" "text/plain; charset=utf-8"}
             :body (bytes/encode-utf8 (:target request))}))

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
                (http/serve! connection {} handle!
                  (fn [error _]
                    (when error (println (ex-data error)))))))))))))
```

This example serves one connection. The application owns the listener and chooses when to accept more connections.
Use the [TCP listener contract](../portable-tcp/README.md) for bind addresses, backlog, accept deadlines, and listener cancellation.
JVM scripts must keep their process alive while listening and serving. Workers and deadlines use daemon threads.

For HTTPS, pass the accepted TCP handle to [tls/accept!](../portable-tls/README.md#server-handshakes).
After the handshake succeeds, call `serve!` with the TLS handle and `{:tls? true}`.
Negotiate `"http/1.1"`, or omit ALPN. Other negotiated protocols throw `:unsupported-alpn` before ownership is taken.
TCP accept and TLS handshake deadlines are separate from the HTTP serving deadline.

| Serving option | Contract |
| --- | --- |
| `:tls?` | Boolean, default false; selects the required handle type |
| `:timeout-ms` | Whole exchange deadline, including request reads, handler wait, and response writes; default 10000 |
| `:max-body-bytes` | Buffered request-body limit, default 1048576; integer from 0 through 16777216 |
| `:max-response-bytes` | Response-body limit, default 1048576; integer from 0 through 16777216 |

The handle must be open and idle, with no pending reads or writes and output still open.
Non-idle handles throw `:portable/http-error` with reason `:busy`, leaving ownership unchanged.
After `serve!` starts, the application must stop using the source handle directly.
HTTP success, failure, timeout, and cancellation close that connection; the listener remains application-owned.
Cancellation cannot undo transmitted bytes or stop application work already running.
Handlers must yield control so Node's event loop can process deadlines.

### Request and handler values

The handler receives `(request respond!)` asynchronously after the full request is validated and buffered:

```clojure
{:method "POST"
 :target "/echo?q=1"
 :version "1.1"
 :headers {"host" ["example.test"] "content-length" ["3"]}
 :body [0 255 1]}
```

Methods are case-sensitive ASCII strings, limited to 32 characters. Extension methods remain strings, avoiding untrusted keyword creation.
Targets preserve their wire representation. Origin-form paths and `OPTIONS *` are supported; targets are limited to 8192 ASCII characters.
There is no automatic URL, text, or JSON decoding.
Header names are lowercase and values remain vectors, including repeated fields.
HTTP/1.1 requires exactly one valid Host field. HTTP/1.0 permits an omitted Host field.

Requests support fixed-length and chunked bodies. Without either framing header, the request body is empty.
Duplicate lengths, conflicting framing, malformed fields, and invalid trailers are rejected before handler invocation.
Request headers and trailers share the native client's 65536-byte and 100-field limits.
Chunk-size lines are limited to 8192 bytes. Trailers are validated and discarded.
Compressed request bodies are rejected with HTTP 415; there is no automatic request decompression.
`Expect` is rejected with HTTP 417. Upgrades, CONNECT tunnels, proxy-form targets, and extra buffered requests are rejected.
Persistent connections, pipelining, streaming bodies, and HTTP/2 or HTTP/3 serving are outside this contract.

### Responses and completion

Call `(respond! {:status 200 :headers {} :body []})` once, synchronously or later.
Status defaults to 200, headers to an empty string-to-string map, and body to an empty unsigned byte vector.
Final statuses range from 200 through 599. Header names are case-insensitive; duplicates and injection characters are rejected.
Responses use Content-Length and Connection: close. Date defaults to the current UTC HTTP date from `portable-clock`.
Content-Length, Transfer-Encoding, Connection, Trailer, Upgrade, Keep-Alive, and Proxy-Connection are managed response fields.
The final header block is limited to 65536 bytes and 100 fields, including managed fields.

HEAD responses omit wire body bytes while reporting the representation's length.
Statuses 204, 205, and 304 require empty response bodies; 204 and 304 omit Content-Length.
Response content encoding is application-controlled. Precompress byte vectors with `portable-compression` and supply Content-Encoding when needed.
Writes are bounded chunks, with one write pending at a time. Completion confirms local transport acceptance and closure, not peer receipt.

The first committed response wins. Duplicate or late responses return nil and perform no I/O.
Malformed responses throw synchronously from `respond!` before claiming the response, allowing an asynchronous handler to correct them.
An uncaught handler exception before response commitment sends HTTP 500 and completes with `:handler-failed`.
Handler return values are ignored. A handler that never responds reaches the exchange deadline.
Application exceptions after response commitment do not replace the committed response.

Malformed requests receive a bounded empty error response when the transport remains writable.
Oversized bodies receive 413; long targets 414; unsupported content encoding 415; expectations 417; header limits 431; unsupported upgrades or transfer codings 501.
Other request syntax failures receive 400. Serving completes with the original protocol error even when an error response was sent.
Transport failures, deadlines, and cancellation close the connection without guaranteeing an HTTP error response.
Serving errors use `:type :portable/http-error` and `:operation :portable.http/serve!`.

## Browser Fetch limits

Browser clients use Fetch instead of raw TCP sockets.
HTTP serving throws `:portable/unsupported-operation` with operation `:portable.http/serve!` in browsers. Node's `:nodejs` target always uses the portable protocol stack, even when Fetch exists.
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
Fixture servers and independent interoperability clients use native HTTP implementations.
Production clients and serving connections use portable codecs over portable TCP/TLS.
Server contracts cover fixed/chunked requests, HTTP/HTTPS interoperability, HEAD, empty responses, invalid requests, handler errors, and cancellation.
Shared pure server codecs also run in Chrome. Test fixtures are excluded from library jars. Node and local loopback permission are required for the shared suite.

Verification uses macOS, Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, ClojureScript 1.11.132, Node.js 24.22.1, and Chrome 157.0.8103.80.
Linux, Windows, IPv6 endpoints, Firefox, Safari, ClojureCLR, ClojureDart, and jank remain unverified.
