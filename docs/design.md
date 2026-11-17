# Design contract

## Purpose

portable gives library and application authors one API for operations whose host implementations differ.
Consumer code depends on `portable.*` namespaces. Host selection, interop, and reader conditionals belong inside portable.

The repository contains a `.cljc` library scaffold. No platform API family or runtime support guarantee is established by that scaffold.

## Runtime targets

Design APIs for JVM Clojure, ClojureScript in Node and browsers, Babashka, ClojureCLR, ClojureDart, and jank.
Track compatibility per API and runtime, since operations available on a server may be unavailable in a browser.

Clojure documents the `:clj`, `:cljs`, and `:cljr` platform features in its [reader reference](https://clojure.org/reference/reader#_reader_conditionals).
The [ClojureDart compiler](https://github.com/Tensegritics/ClojureDart/blob/main/clj/src/cljd/compiler.cljc) resolves `.cljd` and `.cljc` sources and reads Dart code with `:cljd`.
Shared source is therefore a design option across these targets, subject to each compiler's supported language subset.

[jank](https://jank-lang.org/) provides native Clojure with C++ interop and a `:jank` reader conditional.
jank-specific interop stays behind the public API and needs its own runner and contract checks.

JVM Clojure and Babashka have scaffold load checks. Other targets need runners before compatibility can be claimed.
The generated build uses JVM tooling to package `.cljc` sources; that build setting does not restrict the intended API targets.

## API boundaries

- Define each public concern in its own namespace. Prefer ordinary functions and plain Clojure data.
- Specify units, encoding, nil handling, failures, and execution behavior before implementing host adapters.
- Keep host objects out of public values where a portable value can express the same meaning.
- Report unsupported operations explicitly with structured `ex-info` data. Do not substitute a different operation silently.
- Separate pure transformations from effects such as files, clocks, network access, and environment access.

Reader conditionals and host-specific source files are implementation details.
Prefer direct implementation branches before introducing a protocol or backend registry.

## Adding an API

1. Choose one API family and define its consumer use cases.
2. Write shared behavior tests, including errors and unsupported operations.
3. Implement host adapters behind the same public namespace.
4. Run the shared contract on each claimed target, including browser and server contexts where relevant.
5. Record supported runtime versions and any capability limits with that API's documentation.

## Decisions to make

Choose the first API family and its minimum useful operation set.
Candidates include clocks, environment access, binary/text conversion, paths, filesystem access, and networking.
Choose a distribution license before publishing. Module coordinates and build tooling do not select a license.
