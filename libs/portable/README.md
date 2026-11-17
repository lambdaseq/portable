# portable umbrella

`lambdaseq/portable` depends on every released portable API family.
It contains dependency metadata only. Require each family's namespace directly:

```clojure
(require '[portable.clock :as clock])
(clock/now-ms)
```

Depend on `lambdaseq/portable-clock` or `lambdaseq/portable-bytes` directly when you need only that family.

Run commands from the workspace root:

```sh
clojure -T:build test :modules :portable-clock
clojure -T:build jar :modules :portable
clojure -T:build repl :modules :portable
```

Families share a release version. Local dependency roots become Maven versions during packaging.
See [the workspace README](../../README.md) and [design contract](../../docs/design.md).
