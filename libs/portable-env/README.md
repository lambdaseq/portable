# portable-env

`lambdaseq/portable-env` provides environment variable lookup through `portable.env`.
The `lambdaseq/portable` umbrella also includes it.

```clojure
(require '[portable.env :as env])

(env/get-env "APP_MODE") ; string when present, nil when absent
```

## Contract

`get-env` takes one nonempty string name, without `=` or NUL characters.
It reads the process environment synchronously and returns a string or `nil`.
An empty value remains `""`. Whitespace, Unicode, leading zeros, and string values such as `"false"` remain unchanged.
Names retain their case; lookup follows the host's case rules.
The function does not load dotenv files, read Java system properties, or coerce values.
Host access errors propagate.

Browsers throw `ex-info` for valid names, including absent variables and hosts with a `process.env` shim:

```clojure
{:type :portable/unsupported-operation
 :operation :portable.env/get-env}
```

Invalid names fail validation before host selection, including in browsers:

| Input | `:reason` |
| --- | --- |
| Non-string | `:expected-string` |
| Empty string or name containing `=` or NUL | `:invalid-name` |

These errors carry `:type :portable/invalid-input`, `:operation :portable.env/get-env`, and `:reason`.
Error data excludes environment names and values.

## Runtimes

| Runtime | Behavior |
| --- | --- |
| JVM Clojure and Babashka | Read through `System/getenv` |
| ClojureScript in Node | Read own properties of `process.env` |
| ClojureScript in browsers | Explicit unsupported error |
| Other JavaScript hosts without Node process APIs | Explicit unsupported error |

Contracts run on Clojure 1.12.4 with JDK 25.0.3, Babashka 1.13.219, and ClojureScript 1.11.132.
JavaScript verification uses Node.js 24.22.1 and Chrome 157.0.8103.78.
The JVM build requires JDK 17+; the Node runner requires Node.js 18+.
Firefox, Safari, ClojureCLR, ClojureDart, and jank are unverified.
See the host contracts for [Java](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html#getenv(java.lang.String))
and [Node](https://nodejs.org/api/process.html#processenv).

## Verification

From the workspace root:

```sh
clojure -T:build test :modules :portable-env
clojure -T:build jar :modules :portable-env
```

The test task supplies named fixture variables to its JVM, Babashka, and Node subprocesses.
Fixtures cover empty values, whitespace, Unicode, and strings that resemble numbers or booleans.
Missing-variable checks use unique names. Tests do not enumerate the environment.
Browser contracts check unsupported lookup, validation, and rejection of process shims.
The [browser runner](../portable-clock/README.md#browser-runner) uses the same contract namespace.
Use the workspace test task to run with the required fixtures.
