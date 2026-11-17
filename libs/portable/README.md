# portable umbrella

`lambdaseq/portable` depends on every released portable API family.
It contains dependency metadata only. Require each family's namespace directly:

```clojure
(require '[portable.clock :as clock])
(clock/now-ms)
```

Depend directly on a family when you need only clocks, bytes, environment lookup, or paths.
Coordinates are `lambdaseq/portable-clock`, `lambdaseq/portable-bytes`, `lambdaseq/portable-env`, and `lambdaseq/portable-paths`.

Run commands from the workspace root:

```sh
clojure -T:build test :modules :portable-clock
clojure -T:build jar :modules :portable
clojure -T:build repl :modules :portable
```

Families share a release version. Local dependency roots become Maven versions during packaging.
See [the workspace README](../../README.md) and [design contract](../../docs/design.md).
