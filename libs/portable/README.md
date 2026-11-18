# portable umbrella

`lambdaseq/portable` depends on every released portable API family.
It contains dependency metadata only. Require each family's namespace directly:

```clojure
(require '[portable.clock :as clock])
(clock/now-ms)
```

Depend directly on a family when you need only its APIs.
Coordinates are `lambdaseq/portable-clock`, `lambdaseq/portable-bytes`, `lambdaseq/portable-env`, and `lambdaseq/portable-paths`.
Use `lambdaseq/portable-fs` for filesystem access; it includes `portable-bytes` for its byte and text contract.
Use `lambdaseq/portable-tcp` for asynchronous sockets; it includes `portable-bytes` for byte vectors.
Use `lambdaseq/portable-tls` for verified TLS clients over `portable-tcp`.
Use `lambdaseq/portable-http` for HTTP requests over portable transports or browser Fetch.
Use `lambdaseq/portable-udp` for asynchronous IPv4 unicast datagrams; it includes `portable-bytes`.
Use `lambdaseq/portable-dns` for DNS codecs and queries over `portable-udp` and `portable-tcp`.

Run commands from the workspace root:

```sh
clojure -T:build test :modules :portable-clock
clojure -T:build jar :modules :portable
clojure -T:build repl :modules :portable
```

Families share a release version. Local dependency roots become Maven versions during packaging.
See [the workspace README](../../README.md) and [design contract](../../docs/design.md).
