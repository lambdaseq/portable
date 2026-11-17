# portable-tls

`lambdaseq/portable-tls` provides verified asynchronous TLS clients through `portable.tls`.
The `lambdaseq/portable` umbrella also includes it.
Each connection opens through `portable-tcp`, transfers socket ownership, and uses native TLS encryption.

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

| Function | Behavior |
| --- | --- |
| `connect!` | Return a cancellable opaque handle immediately; callback receives the verified connection |
| `read!` | Callback receives a nonempty plaintext byte vector, or nil at native EOF |
| `write!` | Encrypt unsigned bytes; callback receives the locally accepted byte count |
| `shutdown-output!` | Send TLS `close_notify` and close output; callback receives nil |
| `close!` | Abort TLS and its transport; cancel pending operations; return nil; safe to repeat |
| `get-session-info` | Return `{:version "TLSv1.3" :alpn "http/1.1"}` or another negotiated version/protocol |

Use the successful connect callback before I/O. Application bytes cannot be read or written during authentication.
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

### Native TLS limits

Cryptography, trust-store policy, cipher selection, and TLS record handling use JSSE or Node's native TLS implementation.
EOF and output-shutdown behavior follow those implementations and the negotiated TLS version.
TCP half-close semantics are not guaranteed for TLS 1.2. EOF alone does not prove application-message completeness.
The API provides clients. Public STARTTLS upgrades, mTLS configuration, custom cipher lists, and TLS servers are outside this contract.
Browsers and other JavaScript targets without the Node adapter are unsupported.
The Node adapter requires the ClojureScript `:nodejs` target with CommonJS `require`.

## Errors

Invalid inputs throw synchronously with `:type :portable/invalid-input`, `:operation`, and `:reason`.
TCP validation reasons also apply. TLS adds `:invalid-server-name`, `:invalid-ca-certs`, and `:invalid-alpn`.
Unknown options, including attempts to disable verification, are rejected.
Options, callbacks, and payloads are validated before runtime selection; handles are validated afterward.

Every valid browser call throws synchronously with `:type :portable/unsupported-operation` and its TLS `:operation`.
Network and handshake failures reach callbacks with `:type :portable/tls-error`, `:operation`, and `:reason`.
Reasons include `:certificate-rejected`, `:invalid-ca`, and `:handshake-failed`, plus TCP lifecycle and transport reasons.
Certificate rejection includes untrusted chains, expiry, and identity mismatch.
Native causes are retained. Error data excludes certificate contents and application bytes.

## Verification

From the workspace root:

```sh
clojure -T:build test :modules :portable-tls
clojure -T:build jar :modules :portable-tls
```

All process-runtime contracts connect to local Node fixture servers using ephemeral loopback ports.
Node is required to run the JVM and Babashka TLS tests as well.
Fixtures check trusted byte streams, both protocol versions, SNI, ALPN, certificate rejection, deadlines, and cancellation.
Browser contracts check unsupported operations and input validation.
The [browser runner](../portable-clock/README.md#browser-runner) also applies to this library.
Test certificates and their public fixture key are excluded from library jars.

Verification uses macOS, Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, and ClojureScript 1.11.132.
JavaScript verification uses Node.js 24.22.1 and Chrome 157.0.8103.78.
Build prerequisites match the other families. Tests require local loopback networking permission.
Linux, Windows, IPv6 endpoints, Firefox, Safari, ClojureCLR, ClojureDart, and jank remain unverified.
