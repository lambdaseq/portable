# portable-tls

`lambdaseq/portable-tls` provides verified TLS clients and asynchronous server handshakes through `portable.tls`.
The `lambdaseq/portable` umbrella also includes it.
Client connections open through `portable-tcp`; server handshakes take ownership of connected TCP handles.
Both roles use native TLS encryption.

```clojure
(require '[portable.tls :as tls])

(def connection
  (tls/connect! {:host "example.com" :port 443 :timeout-ms 5000
                 :alpn ["http/1.1"]}
    (fn [error connected]
      (if error
        (println (ex-data error))
        (do
          (println (tls/get-session-info connected))
          (tls/close! connected))))))
```

## Contract

The callback model, byte representation, limits, and deadlines follow the [TCP contract](../portable-tcp/README.md).
Callbacks receive `(error value)` asynchronously and complete once.
Errors supply nil as the value. Success supplies nil as the error.
JVM callbacks can run concurrently on daemon threads; Node callbacks use its event loop.
JVM scripts must keep the process alive while listening and serving.

| Function | Behavior |
| --- | --- |
| `connect!` | Return a cancellable opaque handle immediately; callback receives the verified connection |
| `accept!` | Take ownership of connected TCP; return a cancellable TLS handle; callback receives the negotiated server connection |
| `read!` | Callback receives a nonempty plaintext byte vector, or nil at native EOF |
| `write!` | Encrypt unsigned bytes; callback receives the locally accepted byte count |
| `shutdown-output!` | Send TLS `close_notify` and close output; callback receives nil |
| `close!` | Abort TLS and its transport; cancel pending operations; return nil; safe to repeat |
| `get-session-info` | Return `{:version "TLSv1.3" :alpn "http/1.1"}` or another negotiated version/protocol |

Use the successful connect or accept callback before TLS I/O. Application bytes cannot be read or written during authentication.
TCP and TLS handles are distinct; mixing their public APIs fails validation.
Session information remains available after closing a successfully authenticated connection.
Before successful authentication, `get-session-info` throws a `:not-connected` TLS error.

Reads default to 65536 bytes, with a maximum of 1048576. Writes accept at most 1048576 bytes, including empty vectors.
One read and one write can be pending. Output shutdown shares the write slot.
Read, write, and output shutdown accept an optional options map before their callback.
Timeouts close the whole connection and settle pending callbacks.
Writes indicate local acceptance and are not retried automatically.

### Connect options

| Option | Meaning |
| --- | --- |
| `:host` | Required TCP destination and default certificate identity |
| `:port` | Required integer from 1 through 65535 |
| `:timeout-ms` | Overall connection deadline, including DNS, TCP, and TLS; default 10000 |
| `:server-name` | Override certificate identity and SNI with a qualified ASCII DNS name |
| `:ca-certs` | Nonempty vector of PEM certificates replacing native default trust anchors |
| `:alpn` | Vector of application protocol names; default empty |

Certificate chain and peer-name verification are always enabled. There is no verification bypass option.
TLS 1.2 and 1.3 are enabled; earlier protocol versions are excluded.
Omitting `:ca-certs` uses native default trust stores, which can differ across runtimes.
Each CA entry contains exactly one PEM certificate. Native certificate parsing occurs asynchronously before the TLS handshake.

Explicit SNI names require at least two DNS labels, a maximum of 253 characters, and no trailing dot.
They cannot be IP addresses. Use ASCII Punycode for internationalized names.
Short hostnames and IP destinations are verified without SNI. Qualified destination names also supply default SNI.
ALPN negotiates an application protocol during the handshake.
Each protocol name must contain 1 through 255 visible ASCII characters. An absent negotiation yields `:alpn nil`.

### Server handshakes

Create a listener with `tcp/listen!`, receive a connection with `tcp/accept!`, then negotiate with `tls/accept!`.
The TCP listener and each TLS connection have independent lifetimes.
`tls/accept!` takes a connected TCP handle, an options map, and a callback `(error connection)`.
It returns an opaque TLS handle immediately. Use `tls/close!` to cancel a pending handshake.

```clojure
(require '[portable.tcp :as tcp])

(defn serve-one! [listener identity]
  (tcp/accept! listener
    (fn [error raw]
      (if error
        (println (ex-data error))
        (tls/accept! raw identity
          (fn [error connection]
            (if error
              (println (ex-data error))
              (do
                (println (tls/get-session-info connection))
                (tls/close! connection)))))))))
```

Call `serve-one!` after listening succeeds. Supply identity PEM contents from the application's credential source.
Close the TCP listener separately when no further connections should be accepted.

| Server option | Meaning |
| --- | --- |
| `:cert-chain` | Required leaf-first vector of 1 through 16 PEM certificates; each at most 65536 characters |
| `:private-key` | Required unencrypted PKCS#8 PEM private key, at most 65536 characters; RSA or EC |
| `:timeout-ms` | Handshake deadline, default 10000; starts after TCP ownership transfer |
| `:alpn` | Server-preference vector of application protocol names; same name rules as client ALPN; default empty |

Each certificate entry contains one certificate. Keys must use `BEGIN PRIVATE KEY`, including RSA keys.
Encrypted keys and traditional PKCS#1 `BEGIN RSA PRIVATE KEY` files are rejected synchronously.
PEM shapes and limits are validated synchronously. Native identity parsing and handshakes run asynchronously.
Malformed native certificate/key data reports `:invalid-identity`; errors return no TLS connection.
Context and certificate/key policy otherwise follow native providers. Each handshake creates its own context.

TCP ownership transfers synchronously after validation. The source handle must be open with no pending read or write.
A failed transfer throws a `:portable/tls-error` with operation `:portable.tls/accept!` and reason `:busy`.
It leaves TCP ownership unchanged. After successful transfer, the source TCP handle is closed to further TCP operations.
Do not consume incoming handshake bytes before transfer. Closing the source handle after transfer leaves TLS ownership intact.
Handshake failure, timeout, or TLS cancellation closes the owned native socket. Retry requires another TCP connection.
Callback completion can race with cancellation; the committed result wins once.
Closing the listener leaves transferred TLS connections open.

Servers present one configured certificate chain. Clients remain responsible for chain and peer-name verification.
Server handshakes enable TLS 1.2 and 1.3 and do not request or authenticate client certificates.
Client SNI can identify the peer's requested name, but certificate selection is fixed; there is no SNI routing callback.
ALPN chooses an overlapping protocol using server preference. Negotiation can be absent when either peer omits ALPN.
Native handling of a nonoverlapping ALPN offer can fail the handshake.

### Native TLS limits

Cryptography, trust-store policy, cipher selection, and TLS record handling use JSSE or Node's native TLS implementation.
EOF and output-shutdown behavior follow those implementations and the negotiated TLS version.
TCP half-close semantics are not guaranteed for TLS 1.2. EOF alone does not prove application-message completeness.
Public client STARTTLS upgrades, mTLS configuration, session-resumption controls, SNI routing, and custom cipher lists are outside this contract.
Browsers and other JavaScript targets without the Node adapter are unsupported.
The Node adapter requires the ClojureScript `:nodejs` target with CommonJS `require`.

## Errors

Invalid inputs throw synchronously with `:type :portable/invalid-input`, `:operation`, and `:reason`.
TCP validation reasons also apply. TLS adds `:invalid-server-name`, `:invalid-ca-certs`, `:invalid-cert-chain`, `:invalid-private-key`, and `:invalid-alpn`.
Unknown options, including attempts to disable verification, are rejected.
Options, callbacks, and payloads are validated before runtime selection; handles are validated afterward.

Every valid browser call throws synchronously with `:type :portable/unsupported-operation` and its TLS `:operation`.
Network and handshake failures reach callbacks with `:type :portable/tls-error`, `:operation`, and `:reason`.
Reasons include `:certificate-rejected`, `:invalid-ca`, `:invalid-identity`, and `:handshake-failed`, plus TCP lifecycle and transport reasons.
Certificate rejection includes untrusted chains, expiry, and identity mismatch.
Native causes are retained. Error data excludes certificate contents and application bytes.

## Verification

From the workspace root:

```sh
clojure -T:build test :modules :portable-tls
clojure -T:build jar :modules :portable-tls
```

Client contracts connect to local Node fixture servers using ephemeral loopback ports.
Server contracts listen through portable TCP and negotiate through portable TLS, using both portable and independent native Node clients.
Node is required to run the JVM and Babashka TLS tests as well.
Fixtures check trusted byte streams, both protocol versions, SNI, ALPN, certificate rejection, deadlines, and cancellation.
Server cases cover RSA and P-256 EC identities, TLS 1.2/1.3 interoperability, binary I/O, ALPN, delayed upgrades, ownership, and session access.
They also check malformed identities, plaintext rejection, busy transfer, deadlines, and cancellation.
Browser contracts check unsupported client/server operations and input validation.
The [browser runner](../portable-clock/README.md#browser-runner) also applies to this library.
Test certificates and their public fixture key are excluded from library jars.

Verification uses macOS, Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, and ClojureScript 1.11.132.
JavaScript verification uses Node.js 24.22.1 and Chrome 157.0.8103.80.
Build prerequisites match the other families. Tests require local loopback networking permission.
Linux, Windows, IPv6 endpoints, Firefox, Safari, ClojureCLR, ClojureDart, and jank remain unverified.
