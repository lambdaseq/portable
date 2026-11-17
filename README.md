# portable

A library project for unified APIs across Clojure implementations.
Library and app builders use shared `portable.*` namespaces while portable handles host APIs and reader conditionals
internally.

Each API family is a separate library. `lambdaseq/portable` includes all families as dependencies.

## Quickstart

### Prerequisites

Clojure CLI and JDK 17+ are required for the JVM build.
The full contract suite also requires Babashka, Node.js 18+, and Chrome or Chromium.

### Setup

```sh
clojure -P -M:dev
```

### Run

```sh
clojure -M:dev -e "(require '[portable.clock :as clock]) (println (clock/now-ms))"
```

For an interactive workspace REPL, run `clojure -M:dev`.

### Verify it worked

The load check prints integer Unix epoch milliseconds.

```sh
clojure -T:build ci
jar tf libs/portable/target/portable-0.1.0.jar
jar tf libs/portable-clock/target/portable-clock-0.1.0.jar
jar tf libs/portable-bytes/target/portable-bytes-0.1.0.jar
jar tf libs/portable-env/target/portable-env-0.1.0.jar
jar tf libs/portable-paths/target/portable-paths-0.1.0.jar
jar tf libs/portable-fs/target/portable-fs-0.1.0.jar
```

The umbrella jar contains dependency metadata. Family jars contain their public `.cljc` namespaces.
Shared contracts run on JVM Clojure, Babashka, Node, and headless Chrome.
Browser environment and filesystem operations are verified to fail explicitly.

### Troubleshooting

- Run build commands from this workspace root. Select `:portable-clock` to test or build clocks alone.
- Set `CHROME_BIN` to the browser executable if automatic detection fails. See the [browser runner](libs/portable-clock/README.md#browser-runner).
- CLR, Dart, and jank need their own compiler and runtime checks; the JVM build does not verify those targets.

## Workspace

| Path                           | Purpose                                                      |
|--------------------------------|--------------------------------------------------------------|
| `libs/portable/`                | Dependency-only umbrella, `lambdaseq/portable`                |
| `libs/portable-clock/`          | Clock API and shared tests, `lambdaseq/portable-clock`         |
| `libs/portable-bytes/`          | UTF-8, Base64, and hex, `lambdaseq/portable-bytes`              |
| `libs/portable-env/`            | Environment lookup, `lambdaseq/portable-env`                  |
| `libs/portable-paths/`          | POSIX and Windows paths, `lambdaseq/portable-paths`            |
| `libs/portable-fs/`             | Synchronous filesystem access, `lambdaseq/portable-fs`        |
| `test-support/`                | Shared test reporting and browser page; development only     |
| `build.clj`                    | Self-contained build functions and module registration       |
| `docs/design.md`               | Runtime targets, API boundaries, and implementation criteria |

Build or test with `clojure -T:build build` and `clojure -T:build test`.
Add the umbrella for all families, or depend on one family directly.
Consumer namespaces stay the same in either case.
For a sibling local project, use:

```clojure
{:deps {lambdaseq/portable {:local/root "../portable/libs/portable"}}}
```

For clocks alone, use `lambdaseq/portable-clock` with `:local/root "../portable/libs/portable-clock"`.
See the [clock API and runtime requirements](libs/portable-clock/README.md).
For byte conversions alone, use `lambdaseq/portable-bytes` with `:local/root "../portable/libs/portable-bytes"`.
See the [byte representation and conversion rules](libs/portable-bytes/README.md).
For environment lookup alone, use `lambdaseq/portable-env` with `:local/root "../portable/libs/portable-env"`.
See the [environment lookup contract](libs/portable-env/README.md).
For lexical paths alone, use `lambdaseq/portable-paths` with `:local/root "../portable/libs/portable-paths"`.
See the [path styles and operations](libs/portable-paths/README.md).
For filesystem access alone, use `lambdaseq/portable-fs` with `:local/root "../portable/libs/portable-fs"`.
See the [filesystem operations and browser behavior](libs/portable-fs/README.md).

## Portability contract

Consumers should not branch on the host runtime to use a supported portable API.
Each API defines its argument shapes, return values, error data, and synchronous or asynchronous behavior.
Host-specific code stays inside portable. Unsupported operations must fail explicitly rather than silently change
semantics.

| Target                          | API verification                                   |
|---------------------------------|----------------------------------------------------|
| JVM Clojure                     | Shared contract suite and source jar build          |
| Babashka                        | Shared contract suite                               |
| ClojureScript, Node             | Shared contract suite                               |
| ClojureScript, browser          | Shared contract suite in headless Chrome            |
| ClojureCLR                      | Target; compiler and contract suite not configured |
| ClojureDart                     | Target; compiler and contract suite not configured |
| jank                            | Target; compiler and contract suite not configured |

See [the design contract](docs/design.md) before adding an API or claiming support for a target.
