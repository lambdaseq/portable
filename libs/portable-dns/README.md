# portable-dns

`lambdaseq/portable-dns` provides DNS codecs and single-question queries through `portable.dns`.
Queries use portable's wire codec over `portable-udp` and `portable-tcp`, with no host DNS resolver API.
The codecs work in browsers. Browser network queries fail explicitly.
The `lambdaseq/portable` umbrella includes this library.

```clojure
(require '[portable.dns :as dns])

(def wire (dns/encode-query {:id 42 :name "example.com" :type :aaaa}))
(dns/decode-message wire)
;; The decoded query has one question: example.com., AAAA, class IN.
```

```clojure
;; Requires a DNS server listening on this loopback address and port.
(def cancel!
  (dns/query! {:name "example.com"
               :type :a
               :server {:host "127.0.0.1" :port 5353}
               :timeout-ms 2000}
    (fn [error response]
      (println (if error (ex-data error) (:answers response))))))

;; Call (cancel!) to cancel pending work. Repeating it is safe.
```

## Queries

`query!` accepts an options map and an error-first callback `(error response)`.
It returns an idempotent cancellation function. Callbacks complete once, asynchronously.
JVM callbacks can run concurrently, including before the initiating function returns.
Input errors and unsupported runtimes throw synchronously before opening sockets.

| Option | Contract |
| --- | --- |
| `:name` | Required DNS presentation name |
| `:type` | Default `:a`; also `:aaaa`, `:cname`, `:ns`, `:ptr`, `:mx`, `:soa`, `:txt`, `:srv`, `:any`, or a numeric type |
| `:server` | Required map containing an IPv4 literal `:host` and optional `:port`, default 53 |
| `:transport` | Default `:udp`, with TCP fallback on matched truncation; `:tcp` forces TCP |
| `:recursion-desired?` | Boolean, default true; requests recursion from the selected server |
| `:timeout-ms` | Whole-query deadline, default 10000; integer from 1 through 2147483647 |
| `:max-message-bytes` | Response limit, default 65535; integer from 12 through 65535 |

Unknown options are rejected. Server ports range from 1 through 65535.
Servers use canonical dotted-decimal IPv4 addresses. Hostnames, IPv6 endpoints, multicast, and broadcast are outside the query contract.
AAAA records can be queried and decoded through an IPv4 server.
Numeric types range from 1 through 65535, excluding transfer types IXFR and AXFR.

Each query uses `portable-random` to generate a secure transaction ID and owns its socket.
The ID is drawn once with `generate-int! 65536` and reused across TCP fallback.
UDP binds an ephemeral port. Replies must match the selected server, ID, opcode, question, and recursion-desired flag.
Mismatched UDP replies are ignored until a matching reply arrives or the original deadline expires.
Matching malformed replies fail explicitly. TCP response mismatches also fail explicitly.

Queries use classic DNS without EDNS. Accepted UDP responses are limited to 512 bytes and the configured response limit.
A matching truncated UDP response triggers TCP fallback, even when its resource records are incomplete.
TCP uses the two-byte DNS length prefix and reads fragmented frames incrementally.
Both transports share the original deadline. A truncated TCP response fails with `:truncated-response`.
Completion, failure, cancellation, and deadlines close owned sockets. Queries are not retried automatically.

This API queries a caller-selected server. It does not discover system resolvers or perform iterative resolution.
CNAME chasing, caching, search suffixes, hosts files, server failover, connection pooling, and pipelining are outside its contract.
It provides plaintext DNS, without DNSSEC validation, DNS-over-TLS, or DNS-over-HTTPS.

## Codec and response values

`encode-query` accepts `:id`, `:name`, `:type`, and `:recursion-desired?`.
The transaction ID is required and ranges from 0 through 65535. It produces an unsigned byte vector.
Queries contain one IN-class question. `decode-message` accepts a complete DNS byte vector, excluding the TCP prefix.
It decodes query and response messages independently of network access.

Names use ASCII presentation syntax. A trailing root dot is optional on input; decoded names include it.
The root name is `.`. ASCII letters are folded to lowercase.
Escaped characters and three-digit decimal byte escapes are supported, including `a\.b.test` and `\255.test`.
Decoded special and non-printable label bytes use decimal escapes. Unicode-to-IDNA conversion belongs to the caller.
Labels are limited to 63 bytes; expanded wire names are limited to 255 bytes.

Decoded messages include `:id`, numeric `:flags`, `:opcode`, and `:rcode`, plus boolean header fields.
The flags include `:response?`, `:authoritative?`, `:truncated?`, `:recursion-desired?`, and `:recursion-available?`.
Sections are vectors under `:questions`, `:answers`, `:authorities`, and `:additionals`.
Questions contain `:name`, `:type`, and numeric `:class`.
Resource records also contain unsigned wire `:ttl` seconds and a `:data` map:

| Record | `:data` shape |
| --- | --- |
| A, AAAA | `{:address "192.0.2.1"}` or a hexadecimal IPv6 address with zero compression |
| CNAME, NS, PTR | `{:name "target.example."}` |
| MX | `{:preference 10 :exchange "mail.example."}` |
| SOA | `:primary`, `:mailbox`, `:serial`, `:refresh`, `:retry`, `:expire`, `:minimum` |
| TXT | `{:strings [[byte ...] ...]}`; each character string retains its bytes |
| SRV | `{:priority 10 :weight 20 :port 443 :target "service.example."}` |
| Unknown types or unsupported classes | `{:bytes [byte ...]}` |

Known type codes become keywords; unknown codes remain integers. Typed record decoding applies to class IN.
There is no automatic TXT-to-UTF-8 conversion. Use `portable-bytes` when text decoding is appropriate.
NXDOMAIN, SERVFAIL, and other DNS response codes are ordinary responses, distinguished by numeric `:rcode`.
An empty answer section with `:rcode 0` differs from NXDOMAIN, which has `:rcode 3`.
The RCODE field exposes the header's low four bits. Extended EDNS codes and DNSSEC flags are not interpreted or validated.

The decoder rejects malformed lengths, reserved labels, trailing bytes, and invalid compression.
Pointers must reference previously parsed label boundaries; forward pointers and cycles are rejected.
Pointer chains are limited to 128 jumps. SRV target compression is rejected.
Messages are limited to 65535 bytes and 1024 total questions and records.

## Errors and verification

Input errors use `:type :portable/invalid-input`, the qualified operation, and a reason.
Protocol and callback errors use `:type :portable/dns-error` with the same fields.
Reasons include `:timeout`, `:cancelled`, `:invalid-compression`, `:incomplete-message`, `:incomplete-response`, and `:message-too-large`.
Transport and entropy reasons can also propagate. Entropy failures reach the callback before socket creation.
Native causes are retained; error data excludes query names and response bodies.
Browser `query!` throws `:type :portable/unsupported-operation` with `:operation :portable.dns/query!`.
Node requires ClojureScript's CommonJS `:nodejs` target. Browser workers do not gain raw socket access.

```sh
clojure -T:build test :modules :portable-dns
clojure -T:build jar :modules :portable-dns
```

Shared codec contracts run on JVM, Babashka, Node, and Chrome, including bounded mutations and malformed compression.
Native query contracts use an independent Node UDP/TCP DNS fixture on loopback. Chrome verifies explicit query rejection.
Fixture tests cover correlation, truncation fallback, fragmented TCP, limits, deadlines, and cancellation.
Fixtures are excluded from jars. Native verification requires Node and loopback permission.
Verification uses macOS, Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, ClojureScript 1.11.132, Node.js 24.22.1, and Chrome 157.0.8103.80.
Linux, Windows, Firefox, Safari, ClojureCLR, ClojureDart, and jank remain unverified.
