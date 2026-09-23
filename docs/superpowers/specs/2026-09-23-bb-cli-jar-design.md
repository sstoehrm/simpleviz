# One babashka CLI, installable with install.sh or bbin — design

The launcher's commands move from bash into one babashka namespace, `cli`.
The `install.sh` launcher shrinks to a wrapper around it, and each release
also ships `simpleviz.jar`, which [bbin](https://github.com/babashka/bbin)
installs with no simpleviz-specific setup. `install.sh` and the tarball stay
the primary install; bbin is optional. New along the way: `simpleviz demo`
and `--no-open` (#93).

Figure: `.blend/specs/2026-09-23-bb-cli-jar.edn` (serve it with `simpleviz`).

## Goal

    curl -fsSL https://raw.githubusercontent.com/sstoehrm/simpleviz/main/install.sh | bash
    bbin install https://github.com/sstoehrm/simpleviz/releases/latest/download/simpleviz.jar

    simpleviz graph.edn [suffix] [--debug] [--no-open]
    simpleviz demo [--debug] [--no-open]

Both installs run the same CLI code, so every command behaves the same.
The exceptions are `update` and `clean-all`, which belong to the
`install.sh` install.

## Decisions

| Question | Decision |
|---|---|
| Replace install.sh with bbin? | No. bbin would add a dependency. install.sh and the tarball stay; the jar is added. |
| How bbin gets the compiled frontend | A release jar (`bb uberjar`). The frontend is git-ignored, so a git install (`io.github…`) can't serve a page. |
| Where the launcher logic lives | One namespace, `cli`. The bash launcher is a wrapper that keeps only `update` and `clean-all`. |
| `clean-all` | Stays Linux-only in the wrapper. A bbin install doesn't have it, and the README says so. |
| Examples without an install folder | `simpleviz demo` copies them into a fresh temp folder and serves the comparison. |
| #93 `--no-open` | Folded in, for `serve` and `demo`. |

## Architecture

    user ──► ~/.local/bin/simpleviz (bash wrapper, from install.sh)
               ├─ update, clean-all: handled in bash
               └─ everything else: exec bb --config ~/.simpleviz/bb.edn -m cli "$@"
    user ──► ~/.local/bin/simpleviz (bbin shim) ─► add-classpath simpleviz.jar, cli/-main

Both write `~/.local/bin/simpleviz`, so the last install wins. The README
says to use one or the other.

### Files come from the classpath

The server and the CLI no longer read files relative to the working
directory; the wrapper doesn't `cd` into `~/.simpleviz` anymore.

- `serve/static-response` serves `public/<path>` through
  `(io/resource (str "public" path))`. The existing guards stay: a path
  containing `..` gets 404, and so does a directory. A `file:` URL must be a
  regular file; a `jar:` URL must not end in `/`. The body is the resource's
  input stream.
- `serve/version` reads the `VERSION` resource, falling back to `"dev"`.
- `cli` reads `examples/<file>` resources for `demo`.
- Classpath roots:
  - Repo: `bb.edn :paths ["server" "test" "."]`. The repo root makes
    `public/` and `examples/` resources; the layout doesn't change.
  - Tarball: the release `bb.edn` gets `:paths ["server" "."]`, and
    `bb bundle` writes `VERSION` into the bundle root.
  - Jar: `public/`, `examples/` and `VERSION` are packed at the jar root.

### serve.clj

`start!` is split out of `-main`. It takes `{:file :suffix :port :debug}`,
does today's startup checks (sides resolve, the embedded EDN is readable),
starts http-kit on 127.0.0.1, opens the debug log, and returns the served
description. It throws on failure; `BindException` passes through unchanged
so a caller can try another port. `serve/-main` (`bb serve`) keeps its
current output and exit behavior on top of it.

## CLI (`server/cli.clj`, namespace `cli`)

Errors print `simpleviz: <message>` to stderr and exit 1, as the bash
`die` does now. Messages that users or the skill know stay word for word.
Relative paths resolve from the working directory. Inside `cli.clj`,
`babashka.cli` gets an alias other than `cli`.

| Invocation | Behavior |
|---|---|
| `<graph.edn\|.png> [suffix] [--debug] [--no-open]` | Checks, in order: the file exists (`file not found: <f>`); a second argument ending in `.edn`/`.png` gets the two-file hint; the suffix matches `serve/suffix-re` (`invalid suffix: <s>`); the fork exists (`<fork> not found — create it with: simpleviz fork <file> <suffix>`). Then it picks a random port in 7370–7469 that binds on 127.0.0.1 (retrying on `BindException`), calls `serve/start!`, prints `simpleviz: http://localhost:<port>`, opens it with `clojure.java.browse/browse-url` unless `--no-open` (a failure to open is ignored, like the launcher's `xdg-open … || true`), and blocks. |
| `fork\|promote <graph> <suffix>` | Exactly two arguments, else usage and exit 1. `file not found: <f>` when the graph is missing. Calls the `fork` logic. |
| `init <file>` | One argument. Refuses an existing file (`<f> already exists`). Writes the starter template (moved from `install.sh`, text unchanged) and prints `created <f> — view it with: simpleviz <f>`. |
| `extract …` | `png/-main` with the arguments as given. |
| `check <graph>` | `check/-main`. |
| `demo [--debug] [--no-open]` | Copies every file in `cli/example-files` (a vector of paths under `examples/`) from the classpath into `Files/createTempDirectory("simpleviz-demo-")`, keeping subfolders, and prints `simpleviz: demo files in <dir>`. Then it serves `<dir>/demo.edn` with suffix `next`, like the serve row above. |
| `--version`, `version` | `simpleviz <VERSION>`, or `simpleviz dev`. |
| `-h`, `--help`, no arguments | Usage to stdout, exit 0, as now. Usage lists every command and marks `update` and `clean-all` as install.sh-only. |
| `update` | Reaches the CLI only in a bbin install (or a checkout), because the wrapper takes it first. Prints `simpleviz was installed with bbin; update it with: bbin install https://github.com/sstoehrm/simpleviz/releases/latest/download/simpleviz.jar` and exits 0. |
| `clean-all` | Reaches the CLI only in a bbin install (or a checkout). `clean-all needs the install.sh launcher (Linux)`, exit 1. |
| anything else starting with `-` | Usage to stderr, exit 1. |

## Launcher (install.sh heredoc)

What stays: `SIMPLEVIZ_HOME`, `check_bb` (babashka missing or older than
`MIN_BB`), the `$SIMPLEVIZ_HOME` existence check, `update` (unchanged: it
runs the new release's `install.sh`), and `clean-all`. Everything else is
`exec bb --config "$SIMPLEVIZ_HOME/bb.edn" -m cli "$@"`, run from the
user's working directory.

`clean-all` finds servers by command line, not working directory:
`pgrep -f -- "--config $SIMPLEVIZ_HOME/bb.edn -m cli"`. That matches every
running CLI process of this install, and only `serve` and `demo` are
long-running. The "no running servers" message stays, and it is still
Linux-only. A server started by the previous launcher (before an update)
isn't matched; that's accepted for one release.

`install.sh` itself is unchanged apart from the heredoc: it downloads the
tarball, unpacks it into `~/.simpleviz`, and writes `VERSION` and the
launcher.

## Build and release

- `bb jar [version]` (new; depends on `build`):
  1. Recreates `dist/jar-stage/` with its own `bb.edn`,
     `{:paths ["server" "res"] :deps <repo :deps>}`.
  2. Copies `server/*.clj` → `server/`; `public/` (without
     `js/simpleviz/*_test.mjs`) → `res/public/`; `examples/` →
     `res/examples/`; the version → `res/VERSION`; `LICENSE` and
     `THIRD-PARTY-NOTICES.md` → `res/`.
  3. Runs `bb uberjar ../simpleviz.jar -m cli` in the stage, producing
     `dist/simpleviz.jar` with `Main-Class: cli`.
  4. Fails if the jar holds a `META-INF/maven/<group>/<artifact>/` that
     `THIRD-PARTY-NOTICES.md` doesn't mention as `<group>/<artifact>`.

  The file name stays `simpleviz.jar`, because bbin names the command
  after the part of the URL's file name before the first dot.
- `bb bundle`: adds `"."` to the release `bb.edn` paths and writes `VERSION`.
- `bb jar:smoke` (new): builds the jar, then from a temp directory runs it
  through bbin's http-jar shim (add-classpath, require `cli`, apply
  `-main`), with no bbin involved:
  - `--version`
  - `check examples/demo.edn` (examples copied in first)
  - `demo --no-open`: waits for the URL, fetches `/` and `/api/errors`,
    then stops the process
- `.github/workflows/ci.yml`: runs `bb jar:smoke` after `bb test`.
- `.github/workflows/release.yml`: runs `bb jar $tag` after `bb bundle $tag`,
  then `bb jar:smoke`, and uploads `dist/*.tar.gz dist/simpleviz.jar`.

### Licences

The jar redistributes malli and everything it pulls in. A spike on
2026-09-23 found: borkdude/dynaload, borkdude/edamame, fipp/fipp,
mvxcvi/arrangement, org.clojure/core.rrb-vector, org.clojure/test.check
and org.clojure/tools.reader. `THIRD-PARTY-NOTICES.md` gets one section
per library, with the licence checked at its source during
implementation. The malli paragraph then reads: downloaded at runtime for
the tarball, bundled in the jar. The check in `bb jar` keeps this list
complete.

## Testing

- `test/cli_test.clj` (new) runs `bb -m cli …` as a real process from a
  temp directory:
  - ported from `launcher_test`: two-file form, missing fork named,
    invalid suffix, a folder named like the suffix, fork/promote argument
    count, `check` exit codes 0 and 1 with several warnings, a file name
    starting with `-`, wrong `check` argument count
  - new: `init` writes a file that checks clean and refuses to overwrite;
    `--version` prints `simpleviz dev`; the `update` and `clean-all`
    messages; `cli/example-files` equals the files under `examples/` on
    disk
  - live: `examples/demo.edn --no-open` prints a URL in range, and `/` and
    `/api/errors` answer from the classpath; then the process is killed
- `server_test`: the existing static tests pass through the classpath.
  New: `..` gets 404, a directory gets 404, `VERSION` comes from the
  classpath.
- `launcher_test` (wrapper): keeps the `update` installer test. New: from
  another directory, `check <relative path>` and `--version` reach the
  CLI; `clean-all` stops a server the wrapper started (skipped without
  `pgrep`). The cases moved to `cli_test` are removed here.
- `docs_test`: the init-template test reads `cli/init-template`, not the
  `install.sh` heredoc.
- `bb jar:smoke` in CI and release, as above.

## Docs

- README:
  - Install: install.sh first. Then "With bbin", with the install command
    (which also updates) and a note that bbin and install.sh both write
    `~/.local/bin/simpleviz`.
  - A remark that `clean-all` needs the install.sh launcher on Linux.
  - `simpleviz demo` in Usage, replacing `~/.simpleviz/examples/demo.edn`.
- `docs/guide.md`: `demo`, `--no-open`, and "Try it" uses `simpleviz demo`.
- Plugin skill: `simpleviz demo`; `--no-open` as the way for agents to
  serve; a one-line bbin install note. Re-tested with a subagent run,
  per writing-skills.
- `docs/development.md`: the `cli` namespace, `bb jar` / `bb jar:smoke`,
  both release files, and the `"."` classpath root.

## Out of scope

- bbin's experimental `upgrade`: `update` prints the `bbin install`
  command instead.
- `clean-all` outside Linux or in a bbin install.
- macOS and Windows in CI: the CLI avoids bash, `/proc` and `xdg-open`,
  but only Linux is tested.
- Removing `install.sh` or the tarball.

## Verified before planning (2026-09-23)

- `bb --config <dir>/bb.edn` resolves `:paths` against `<dir>`, from any
  working directory.
- With `"."` on the classpath, `public/…` and `examples/…` resolve as
  resources. A directory (`public/js`) resolves too, which is why the
  directory guard is needed.
- http-kit serves an `InputStream` body from a `jar:` resource.
- `clojure.java.browse/browse-url` exists in babashka (added long before
  1.3.0) and throws when no opener is available.
  `babashka.browse` doesn't exist.
- `java.util.zip.ZipFile` and `babashka.process/destroy-tree` are
  available.

## Follow-up

- TODO: fold the `cli` namespace, the jar build and the wrapper launcher
  into the project's concept graph (blend:deduce). Skipped at spec time on
  2026-09-23.
