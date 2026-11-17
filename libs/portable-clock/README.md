# portable-clock

`lambdaseq/portable-clock` provides wall and monotonic clocks through `portable.clock`.
It is also included by the `lambdaseq/portable` umbrella.

```clojure
(require '[portable.clock :as clock])

(clock/now-ms) ; integer Unix epoch milliseconds

(let [start (clock/monotonic-ms)]
  ;; Perform the work being measured here.
  (- (clock/monotonic-ms) start)) ; elapsed milliseconds
```

## Contract

Both functions take no arguments and return numbers synchronously.

| Function | Result | Use |
| --- | --- | --- |
| `now-ms` | Integer milliseconds since `1970-01-01T00:00:00Z` | UTC timestamps |
| `monotonic-ms` | Finite, nondecreasing milliseconds; fractions allowed | Elapsed time within one running context |

Wall time can jump after clock adjustments. Never use it to measure durations.
Monotonic origins are unspecified, and readings may be negative.
Compare them only within the same process or JavaScript context. Do not persist them as timestamps.
Resolution and behavior during system sleep depend on the host. Repeated readings may be equal.

## Runtimes

| Runtime | Adapter | Verification |
| --- | --- | --- |
| JVM Clojure, JDK 17+ | `System/currentTimeMillis`, `System/nanoTime` | Shared contracts |
| Babashka | JVM-compatible clock methods | Shared contracts |
| ClojureScript, Node.js 18+ | `Date.now`, global `performance.now` | Shared contracts |
| ClojureScript, browser | `Date.now`, global `performance.now` | Shared contracts in headless Chrome |
| ClojureCLR, ClojureDart, jank | No verified adapter | No support claim |

An unavailable operation throws `ex-info` with `:type :portable/unsupported-operation`
and `:operation :portable.clock/now-ms` or `:portable.clock/monotonic-ms`.
JavaScript hosts without `performance.now` receive that error. Wall time is never substituted for monotonic time.

The host contracts are documented by [Java System](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/System.html),
[Date.now](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Date/now),
and [performance.now](https://developer.mozilla.org/en-US/docs/Web/API/Performance/now).

## Verification

From the workspace root:

```sh
clojure -T:build test :modules :portable-clock
clojure -T:build jar :modules :portable-clock
```

The test task runs the same `.cljc` contracts on JVM, Babashka, Node, and headless Chrome.
Verified with Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, and ClojureScript 1.11.132 on Node.js 24.22.1.
Browser contracts are verified with Chrome 157.0.8103.78. Firefox and Safari are unverified.
Tests check epoch units, numeric results, nondecreasing readings, and elapsed milliseconds.
JavaScript tests also check explicit failure when a monotonic clock is unavailable.
The ClojureScript compiler is a test dependency only.

### Browser runner

Install Chrome or Chromium before running the test task. No browser automation package is required.
Detection checks the standard macOS Chrome location, then common Chrome and Chromium executable names on `PATH`.
Set `CHROME_BIN` to an executable path to override detection:

```sh
CHROME_BIN=/usr/bin/chromium clojure -T:build test :modules :portable-clock
```

The browser uses a temporary profile and loads the compiled test page from disk.
Chrome's virtual-time budget allows asynchronous tests to finish before the DOM is collected.
The task requires a successful test summary. Failed assertions, browser errors, and unfinished runs fail the task.
The browser process has a 30-second timeout.
Results and browser diagnostics replace `target/browser-results.html` and `target/browser-stderr.log` in this library.

To inspect the tests interactively, compile from this library directory and open `target/browser.html`:

```sh
clojure -M:test-browser
cp ../../test-support/browser.html target/browser.html
```
