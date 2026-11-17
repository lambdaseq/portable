# Design contract

## Purpose

portable gives library and application authors one API for operations whose host implementations differ.
Consumer code depends on `portable.*` namespaces. Host selection, interop, and reader conditionals belong inside portable.

Each API family lives in its own library and owns its public namespaces and shared contracts.
`lambdaseq/portable-clock` owns `portable.clock`.
`lambdaseq/portable-bytes` owns `portable.bytes` and uses immutable unsigned byte vectors across runtimes.
`lambdaseq/portable-env` owns `portable.env`, with process lookup and explicit browser rejection.
`lambdaseq/portable-paths` owns `portable.paths`, with explicit POSIX and Windows syntax independent of the host.
`lambdaseq/portable-fs` owns `portable.fs`, with synchronous native filesystem access and explicit browser rejection.
`lambdaseq/portable-tcp` owns `portable.tcp`, with asynchronous native sockets and explicit browser rejection.
`lambdaseq/portable-tls` owns `portable.tls`, with verified TLS layered over portable TCP.
The dependency-only `lambdaseq/portable` umbrella includes every released family without forwarding functions.
Families share a release version. Keep them independent unless a concrete shared concern requires a dependency.

## Runtime targets

Design APIs for JVM Clojure, ClojureScript in Node and browsers, Babashka, ClojureCLR, ClojureDart, and jank.
Track compatibility per API and runtime, since operations available on a server may be unavailable in a browser.

Clojure documents the `:clj`, `:cljs`, and `:cljr` platform features in its [reader reference](https://clojure.org/reference/reader#_reader_conditionals).
The [ClojureDart compiler](https://github.com/Tensegritics/ClojureDart/blob/main/clj/src/cljd/compiler.cljc) resolves `.cljd` and `.cljc` sources and reads Dart code with `:cljd`.
Shared source is therefore a design option across these targets, subject to each compiler's supported language subset.

[jank](https://jank-lang.org/) provides native Clojure with C++ interop and a `:jank` reader conditional.
jank-specific interop stays behind the public API and needs its own runner and contract checks.

All implemented families run shared contracts on JVM Clojure, Babashka, and ClojureScript in Node and headless Chrome.
The browser runner uses the same JavaScript contracts, including asynchronous elapsed-time checks.
CLR, Dart, and jank remain targets without verified adapters.
Families share development test support. Filesystem byte and text operations depend on `portable-bytes` for their shared representation and encoding.
TCP also depends on `portable-bytes` for unsigned byte vectors.
TLS depends on TCP for connection establishment and lifecycle, and on bytes for plaintext vectors.
The generated build uses JVM tooling to package `.cljc` sources; that build setting does not restrict the intended API targets.

## API boundaries

- Define each public concern in its own namespace. Prefer ordinary functions and plain Clojure data.
- Specify units, encoding, nil handling, failures, and execution behavior before implementing host adapters.
- Keep host objects out of public values where a portable value can express the same meaning.
- Report unsupported operations explicitly with structured `ex-info` data. Do not substitute a different operation silently.
- Separate pure transformations from effects such as files, clocks, network access, and environment access.

Reader conditionals and host-specific source files are implementation details.
Prefer direct implementation branches before introducing a protocol or backend registry.

## Networking layers

Build network protocols from native socket transports upward.
`portable-tcp` defines asynchronous connection lifecycle, byte reads and writes, deadlines, and output shutdown.
TCP sockets use opaque resource handles; payloads and error data remain portable values.
TLS uses host security implementations above TCP. HTTP protocol encoding and parsing belong to portable itself.
TLS transfers ownership of a connected TCP socket and shares bounded I/O and deadline handling.
Certificate and peer-name verification are mandatory. TLS and TCP handles remain distinct.
Browser HTTP remains unsupported so every supported request uses portable's protocol stack.
Do not substitute Fetch, browser HTTP clients, or host HTTP clients for that stack.
Shared protocol codecs can still be tested in browsers without network access.

## Adding an API

1. Define the family's use cases, create its library, and add it to the umbrella and build registry.
2. Write shared behavior tests, including errors and unsupported operations.
3. Implement host adapters behind the same public namespace.
4. Run the shared contract on each claimed target, including browser and server contexts where relevant.
5. Record supported runtime versions and any capability limits with that API's documentation.

## Decisions to make

Clocks expose `now-ms` and `monotonic-ms`; see the [clock contract](../libs/portable-clock/README.md).
Bytes expose UTF-8, Base64, and hex conversions; see the [byte contract](../libs/portable-bytes/README.md).
Environment lookup exposes `get-env`; see the [environment contract](../libs/portable-env/README.md).
Paths expose pure lexical operations; see the [path contract](../libs/portable-paths/README.md).
Filesystem operations use native host paths; see the [filesystem contract](../libs/portable-fs/README.md).
TCP exposes asynchronous client sockets; see the [TCP contract](../libs/portable-tcp/README.md).
TLS exposes verified client connections; see the [TLS contract](../libs/portable-tls/README.md).
Define HTTP encoding, parsing, and request contracts above these transports before implementation.
Choose a distribution license before publishing. Module coordinates and build tooling do not select a license.
