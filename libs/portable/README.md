# portable library

Unified host APIs for Clojure library and application builders.
Public namespaces live under `portable.*` in `.cljc` files.

Run commands from the workspace root:

```sh
clojure -T:build test :modules :portable
clojure -T:build jar :modules :portable
clojure -T:build repl :modules :portable
```

The coordinate is `lambdaseq/portable`.
The test directory is reserved for shared API contracts; runtime APIs are not implemented by the scaffold.
See [the workspace README](../../README.md) and [design contract](../../docs/design.md).
