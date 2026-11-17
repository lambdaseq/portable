# portable-fs

`lambdaseq/portable-fs` provides synchronous filesystem operations through `portable.fs`.
The `lambdaseq/portable` umbrella also includes it.
JVM Clojure, Babashka, and Node use native filesystems. Browsers throw explicit unsupported errors.

```clojure
(require '[portable.fs :as fs])

(fs/create-dirs! "data")
(fs/write-text! "data/message.txt" "λ😀")
(fs/read-text "data/message.txt") ; => "λ😀"
(fs/list-dir "data")             ; => ["message.txt"]
(fs/stat "data/message.txt")     ; => {:type :file, :size 6}
(fs/delete! "data/message.txt")  ; => true
```

## Contract

Paths are nonempty native path strings without NUL. Relative paths use the process working directory.
Paths pass to the host without lexical normalization, variable expansion, or home-directory expansion.
`portable.paths` provides separate lexical operations with explicit syntax modes.
All calls complete synchronously; Node calls block its event loop.

| Function | Result and behavior |
| --- | --- |
| `read-bytes` | Return an unsigned byte vector from a regular file |
| `write-bytes!` | Create or truncate a regular file; return nil |
| `read-text` | Return strict UTF-8 text, preserving BOMs |
| `write-text!` | Create or truncate a file as strict UTF-8; return nil |
| `create-dirs!` | Create missing directories, including parents; return nil when directories already exist |
| `list-dir` | Return a sorted vector of immediate entry names, including hidden names |
| `stat` | Return metadata without following the final symlink; return nil when absent |
| `delete!` | Delete one entry; return true when deleted, false when absent |

Byte vectors and UTF-8 rules come from [portable-bytes](../portable-bytes/README.md).
Writes validate all content before opening a file. They replace content without appending or creating parent directories.
Repeating a write with the same data leaves the same content.
Writes are not atomic replacements and do not promise crash durability.
Malformed UTF-8 and unpaired UTF-16 surrogates fail explicitly.

Metadata is plain data: `{:type :file :size n}`, `{:type :directory}`, `{:type :symlink}`, or `{:type :other}`.
File sizes are integer bytes. Directory and symlink metadata omit size.
Listings exclude `.` and `..`, return names rather than full paths, and never recurse.

Reads and writes follow symlinks to regular files. Listing follows a symlink to a directory.
Writing through a dangling symlink can create its target, following native filesystem behavior.
Metadata and deletion operate on the final link itself. Intermediate directory symlinks follow native behavior.
Deletion never recurses and rejects nonempty directories.
These operations do not prevent concurrent filesystem changes between checks and access.

## Errors and browsers

Every valid browser call throws `ex-info` with `:type :portable/unsupported-operation` and its `:operation`.
Process and require shims do not enable filesystem access.
The Node adapter requires the ClojureScript `:nodejs` target with CommonJS `require`.
Other JavaScript targets also report unsupported operations.

Invalid inputs throw `ex-info` with `:type :portable/invalid-input`, `:operation`, and `:reason`.
Path validation precedes payload validation, and both precede filesystem access.

| Reason | Meaning |
| --- | --- |
| `:expected-string` | Path or text is not a string |
| `:invalid-path` | Path is empty, contains NUL, or is rejected by the native path parser |
| `:expected-byte-vector` | Content is not a vector of integers from 0 through 255 |
| `:invalid-unicode` | Text contains an unpaired surrogate |
| `:invalid-utf8` | File bytes are malformed UTF-8 |

I/O failures throw `ex-info` with `:type :portable/fs-error`, `:operation`, `:reason`, and the native cause.
Reasons include `:not-found`, `:already-exists`, `:not-directory`, `:is-directory`, `:not-file`, `:not-empty`, and `:access-denied`.
Reasons reflect native error categories; unclassified failures use `:io-error`.
Only missing entries become nil from `stat` or false from `delete!`. Access failures remain errors.
Operations are keywords such as `:portable.fs/read-text` and `:portable.fs/write-bytes!`.

## Verification

From the workspace root:

```sh
clojure -T:build test :modules :portable-fs
clojure -T:build jar :modules :portable-fs
```

The shared suite uses isolated temporary directories and removes them after each process-runtime contract.
It checks content replacement, metadata, sorted listing, errors, strict text conversion, and symlink behavior.
Browser contracts check validation and unsupported errors for every operation, including with process and require shims.
Symlink contracts require permission to create symbolic links.
The [browser runner](../portable-clock/README.md#browser-runner) also applies to this library.

Verification uses macOS with Clojure 1.12.4 on JDK 25.0.3, Babashka 1.13.219, and ClojureScript 1.11.132.
JavaScript verification uses Node.js 24.22.1 and Chrome 157.0.8103.78.
The JVM build requires JDK 17+; the Node runner requires Node.js 18+.
Linux, Windows, Firefox, Safari, ClojureCLR, ClojureDart, and jank remain unverified.
