# portable

A library project for unified APIs across Clojure implementations.
Library and app builders use shared `portable.*` namespaces while portable handles host APIs and reader conditionals internally.

The workspace contains the library scaffold and design contract. Runtime APIs are defined and implemented one concern at a time.

## Quickstart

### Prerequisites

Clojure CLI and JDK 17+ are required for the JVM build.
Babashka can load the shared source directly.

### Setup

```sh
cd ~/IdeaProjects/portable
clojure -P -M:dev
```

### Run

```sh
clojure -M:dev -e "(require 'portable.core) (println :portable/ready)"
```

For an interactive workspace REPL, run `clojure -M:dev`.

### Verify it worked

The load check prints `:portable/ready`.

```sh
clojure -T:build ci
jar tf libs/portable/target/portable-0.1.0.jar
```

The jar contains `portable/core.cljc`. The test harness has no API contract tests until APIs are implemented.

### Troubleshooting

- Run build commands from this workspace root and select the `:portable` module when needed.
- CLR, Dart, and jank need their own compiler and runtime checks; the JVM build does not verify those targets.

## Workspace

| Path | Purpose |
| --- | --- |
| `libs/portable/src/portable/` | Public `.cljc` API namespaces |
| `libs/portable/test/portable/` | Shared behavior tests for implemented APIs |
| `libs/portable/resources/` | Library resources |
| `build.clj` | Self-contained build functions and module registration |
| `docs/design.md` | Runtime targets, API boundaries, and implementation criteria |

Build or test with `clojure -T:build build` and `clojure -T:build test`.
The Maven coordinate is `lambdaseq/portable`; consumer namespaces use `portable.*`.
For a sibling local project, use:

```clojure
{:deps {lambdaseq/portable {:local/root "../portable/libs/portable"}}}
```

## Portability contract

Consumers should not branch on the host runtime to use a supported portable API.
Each API defines its argument shapes, return values, error data, and synchronous or asynchronous behavior.
Host-specific code stays inside portable. Unsupported operations must fail explicitly rather than silently change semantics.

| Target | Scaffold verification |
| --- | --- |
| JVM Clojure | Namespace loading and source jar build |
| Babashka | Namespace loading |
| ClojureScript, Node and browser | Target; runner and contract suite not configured |
| ClojureCLR | Target; compiler and contract suite not configured |
| ClojureDart | Target; compiler and contract suite not configured |
| jank | Target; compiler and contract suite not configured |

These checks establish a scaffold, not runtime API compatibility.
See [the design contract](docs/design.md) before adding an API or claiming support for a target.
