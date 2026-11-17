# portable-paths

`lambdaseq/portable-paths` provides lexical path operations through `portable.paths`.
The `lambdaseq/portable` umbrella also includes it.
Every operation is pure and works in browsers without filesystem access.

```clojure
(require '[portable.paths :as paths])

(paths/normalize "/a/../b/")                 ; => "/b/"
(paths/join ["assets" "images" "../logo.png"]) ; => "assets/logo.png"
(paths/basename "/tmp/report.csv")            ; => "report.csv"
(paths/dirname "/tmp/report.csv")             ; => "/tmp"
(paths/extname "report.tar.gz")               ; => ".gz"
(paths/absolute? "/tmp")                      ; => true

(paths/normalize "C:/a/../b" :windows)        ; => "C:\\b"
(paths/join ["C:/data" "file.txt"] :windows) ; => "C:\\data\\file.txt"
```

## Contract

Every function accepts an optional final style keyword: `:posix` or `:windows`.
The default is `:posix` on every runtime, including Windows hosts.
Inputs are strings, except `join`, which takes a vector of strings. Empty strings are allowed; NUL characters are rejected.
Results are strings, except `absolute?`, which returns a boolean.

| Function | Behavior |
| --- | --- |
| `normalize` | Collapse repeated separators and `.` segments; resolve lexical `..` segments |
| `join` | Ignore empty parts, concatenate with separators, then normalize |
| `basename` | Return the final component, ignoring trailing separators |
| `dirname` | Return the parent, collapsing repeated separators without resolving `.` or `..` |
| `extname` | Return the final component's last dot suffix, including its dot |
| `absolute?` | Check whether the path has a root |

Normalization preserves trailing separators and unresolved leading `..` segments in relative paths.
An empty path or join becomes `"."`. Traversal above an absolute root stays at that root.
Later rooted join parts do not replace earlier parts: `(join ["a" "/b"])` returns `"a/b"`.
`basename` returns `""` for roots, including UNC share roots. `dirname` returns the root itself or `"."` when no parent exists.
Single-component drive-relative parents remain drive-relative: `(dirname "C:file" :windows)` returns `"C:"`.
Dotfiles, `"."`, and `".."` have no extension. A trailing dot is an extension: `"file."` yields `"."`.

### Path styles

POSIX uses `/` as its only separator. Backslashes and drive letters are ordinary text.
Leading separators collapse to one root separator.
Windows accepts both separators and emits backslashes. Drive letter case remains unchanged.
Normalization retains a leading `.\` when removing dot segments would expose a drive prefix in a relative path.
Drive-relative paths such as `C:file` remain relative; drive roots and UNC server/share roots are preserved.
UNC roots normalize with a trailing separator.
Windows current-drive-rooted paths such as `\file` return true from `absolute?`.
They still depend on a current drive when used with native filesystem APIs.
See the distinction in [Microsoft's path formats](https://learn.microsoft.com/en-us/dotnet/standard/io/file-path-formats).

Windows device namespace prefixes such as `\\?\` and `\\.\` are explicitly unsupported.
They throw `ex-info` with `:type :portable/unsupported-operation`, `:operation`, and `:reason :unsupported-path-form`.

Paths retain case, whitespace, Unicode, and literal `~`, `$HOME`, and percent escapes.
The library does not expand variables, resolve URLs, validate native filenames, or consult a working directory.
Normalization does not resolve symbolic links or verify filesystem locations.

### Invalid input

Validation errors throw `ex-info` with `:type :portable/invalid-input`, `:operation`, and `:reason`.
Operations are keywords such as `:portable.paths/normalize`. Error data excludes path values.
Style validation precedes path validation.

| Reason | Meaning |
| --- | --- |
| `:invalid-style` | Style is neither `:posix` nor `:windows` |
| `:expected-string` | A path or join element is not a string |
| `:invalid-path` | A path contains NUL |
| `:expected-path-vector` | Join input is not a vector |

## Verification

From the workspace root:

```sh
clojure -T:build test :modules :portable-paths
clojure -T:build jar :modules :portable-paths
```

Shared contracts cover both styles on JVM, Babashka, Node, and headless Chrome.
They check roots, drive-relative paths, UNC paths, dotfiles, trailing separators, validation, and normalization idempotence.
Verification uses Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, ClojureScript 1.11.132, Node.js 24.22.1, and Chrome 157.0.8103.78.
Build prerequisites and the [browser runner](../portable-clock/README.md#browser-runner) match the other families.
Firefox, Safari, ClojureCLR, ClojureDart, and jank remain unverified.
