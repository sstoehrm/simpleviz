# Fork, promote, and compare by suffix — design

Two new CLI operations and a new shape for compare mode: a graph is
compared against a *fork* of itself named by a suffix, so that following
a ref inside the comparison compares the referenced file against its own
fork. Two-file compare (`simpleviz old.edn new.edn`) is removed.

Figure: `.blend/specs/2026-09-19-fork-promote.edn` (serve it with `simpleviz`).

## Goal

    simpleviz fork graph.edn next      # graph-next.edn + a fork of every referenced file
    simpleviz graph.edn next           # compare graph.edn -> graph-next.edn, refs on
    simpleviz promote graph.edn next   # each fork replaces its base file

Editing the forks (in the page or by hand) shows up in the comparison;
following a ref opens the comparison of the referenced file and its fork.

## Naming

- **Fork of a file**: `<dir>/<stem>-<suffix>.<ext>` — `examples/demo.edn`
  with suffix `next` is `examples/demo-next.edn`; `api/internals.png` is
  `api/internals-next.png`.
- **Suffix**: one or more of letters, digits, `_`, `.`, `-`. Anything
  else (including `/`) is refused with `invalid suffix: <s>`.
- Refs in forked files are NOT rewritten: a fork is a byte-for-byte copy.
  Both sides of every comparison therefore carry the same ref strings,
  and pairing happens by name on the server.

## CLI

| Command | Effect |
|---|---|
| `simpleviz graph.edn` | serve, unchanged |
| `simpleviz graph.edn <suffix>` | compare `graph.edn` (old) → fork (new), refs available |
| `simpleviz fork graph.edn <suffix>` | fork the file and its ref closure |
| `simpleviz promote graph.edn <suffix>` | move every fork in the closure over its base |
| `simpleviz old.edn new.edn` | removed; when the second argument is an existing file the launcher exits 1 with `two-file compare was replaced: simpleviz fork graph.edn <suffix>, then simpleviz graph.edn <suffix>` |

- Launcher (`install.sh`): `serve` takes one file plus an optional suffix
  and `--debug`. When the fork of the root file does not exist it exits 1
  with `graph-next.edn not found — create it with: simpleviz fork graph.edn next`.
  `fork` and `promote` delegate to `bb fork` / `bb promote` from
  `SIMPLEVIZ_HOME`, the file argument passed through `realpath` (like
  `extract`). Usage text lists all three forms.
- `bb.edn`: `serve` becomes `bb serve graph.edn|.png [suffix] [--port N] [--debug]`;
  new tasks `fork` and `promote` (`bb fork graph.edn suffix`); the release
  `bb.edn` written by `bundle` gets the same three; `bb dev [graph.edn [suffix]]`.
- `serve/parse-args` returns `{:file f :suffix s-or-nil :port n :debug b}`;
  `:old-file` is gone. An invalid suffix is a usage error.

## Fork and promote — `server/fork.clj`

`(-main "fork"|"promote" file suffix)`, invoked by the bb tasks.

- **Root folder**: the directory of the given file, canonicalized. Refs
  resolve through `serve/resolve-path` against it, with the same rules as
  the page (no escape, `.edn`/`.png` only, must exist).
- **`ref-targets`** `(ref-targets edn-text)` → the set of string `:ref`
  values on nodes, edges and boxes, read from the graph after
  `graph/normalize` (so vector-form files work too). A parse failure is an
  error naming the file.
- **`closure`** `(closure root start resolve)` → the ordered vector of
  root-relative paths reachable from `start` (itself first, depth-first,
  each file once). `resolve` maps `(current-rel, ref)` to the
  root-relative path to read for that ref. Refs are resolved against the
  directory of the file containing them (`editor/resolve-ref` semantics,
  reimplemented server-side in this namespace: collapse `.` and `..`,
  refuse climbing above the root). A ref that cannot be resolved or read
  prints `warning: <file>: ref "<ref>" <reason>` and is skipped. PNG files
  in the closure contribute their embedded EDN's refs (via `serve/read-source`).
- **fork**:
  1. Compute the closure of the base file, reading base files.
  2. If any fork target exists, print `fork: <path> already exists` and exit
     1 before writing anything.
  3. Copy each file's bytes to its fork; print `created <path>` per file.
- **promote**:
  1. Compute the closure starting at the fork of the given file, reading
     each ref's fork when it exists, else its base (a fork may reference a
     file that was never forked).
  2. For every file in the walk whose fork exists, move the fork over the
     base (`Files/move` with REPLACE_EXISTING, atomic where the filesystem
     allows); print `promoted <path>`. A missing base is fine: the fork
     becomes the base (a ref added in the fork).
  3. Nothing moved: print `nothing to promote` and exit 1.
- Neither command touches files outside the closure. A fork target with a
  different extension than its base never occurs (the suffix goes before
  the extension).

## Server — `server/serve.clj`

- State: `files` becomes `{:root <path> :suffix <s-or-nil>}`. `root-dir`
  stays.
- **`sides`** `(sides rel)` → `{:old <File-or-nil> :new <File>}` for a
  root-relative path (`nil` rel means the root file):
  - no suffix: `{:new (resolve-path root rel)}`;
  - suffix: `{:old base :new fork}` where both go through `resolve-path`
    (so both must exist, be `.edn`/`.png`, and lie below the root). A missing
    fork throws `no <fork-rel> — create it with: simpleviz fork <rel> <suffix>`;
    a missing base throws `no <rel> (only <fork-rel>)`. The page shows the
    message in the banner with the trail intact.
- **Compare export PNG** as the single root (`embedded-old`) is unchanged:
  no suffix, the `file` parameter is refused with
  `refs are not available in an embedded compare`.
- **Startup**: with a suffix, both sides of the root are checked
  (existence plus `read-source`) so a missing fork fails with the message
  above; without a suffix as today.
- **Routes** (`file=` accepted in single and suffix mode):
  - `/api/graph?file=rel`: single → `graph-json` with `:editable` and
    `:path` (as today); suffix → `compare-json` of old vs new with
    `:editable`, `:editable-old` (each side not a PNG), `:file` the new
    side's basename, and `:path` = `rel`. The compare names shown in the
    legend are the two root-relative paths.
  - `/api/version?file=rel`: single → mtime; suffix → `"<old>-<new>"`.
    A refused path reports `{"mtime": 0}` as today.
  - `/api/source?file=rel&which=old|new`: the chosen side (default new),
    404 when refused.
  - `/api/edit` body `path` + `file "old"|"new"`: writes that side of that
    path. Without `path`, the root's sides. Undo stacks stay keyed by
    canonical path.
- `compare-mode?` disappears; the per-request `sides` decides.

## Page — `src/simpleviz/app.cljs`

- The `follow-ref` action and the trail bar are shown when the graph
  payload carries `:path` (single-file and suffix-compare payloads do;
  the embedded-compare PNG payload does not). Today's `(nil? (:compare ..))`
  checks are replaced by `(some? (:path ..))`.
- Following a ref from a compare view navigates exactly as in single
  mode (`?file=&trail=`); the server answers with the compare of the
  target pair. The `:ref` used is the one on the selected element's shown
  attrs (new side for present elements, old side for removed ones).
- Layout: `#trail` and `#diff-legend` render inside one fixed top-center
  column (`#top-center`, flex column, gap 8px), trail first. Only CSS and
  the wrapping element change; the two views keep their ids and styles.
- The tab title in suffix mode reads `graph.edn → graph-next.edn`, per
  file shown.
- In-app help: the Edit section says following works in a suffix
  comparison too.

## Errors

| Case | Behaviour |
|---|---|
| invalid suffix | usage error at startup / from `fork` and `promote` |
| root fork missing at startup | exit 1 with the `create it with: simpleviz fork` message |
| ref target's fork missing | error payload → banner, trail intact |
| ref target's base missing (ref added only in the fork) | error payload `no <rel> (only <fork-rel>)` |
| `file=` on an embedded-compare PNG root | error payload |
| fork target already exists | `fork` exits 1, nothing written |
| nothing to promote | `promote` exits 1 |
| unresolvable ref during fork/promote | warning, skipped |

## Testing

- `test/fork_test.clj`: `ref-targets` (map form, vector form, non-string
  ref ignored); `closure` (transitive, `..` inside root, cycle terminates,
  escaping ref skipped with a warning); `fork` creates every file verbatim
  and refuses when a target exists (no partial writes); `promote` moves
  forks, keeps unforked files, handles a fork-only ref, and reports
  nothing to promote. Fixtures built in a temp dir.
- `test/server_test.clj`: `parse-args` with a suffix and an invalid
  suffix; `sides` in both modes and both missing-side errors;
  `/api/graph?file=` in suffix mode returns a compare payload with `:path`
  and both editable flags; `/api/version` and `/api/source` per side;
  edit `path` + `file "old"` writes the base and undoes independently;
  `file=` refused for an embedded-compare PNG; the existing PNG-sides
  compare test becomes `x.png` + `x-next.png`; every test that set
  `serve/files` uses the new shape.
- Headless Chromium (manual, `dev/cdp.mjs`): serve `examples/demo.edn next`,
  select API, `f r`, assert the URL carries `file=api/internals.edn`, the
  legend names `internals.edn → internals-next.edn`, and the trail shows
  `demo.edn`; click the crumb, assert the root compare is back.

## Examples and docs

- `examples/api/internals-next.edn`: a fork of `internals.edn` with a
  visible change (pool size, one added node and edge) so the demo compare
  can be followed into.
- README: Getting started (fork/compare/promote lines), Exporting (the
  extract flow ends in `simpleviz extract .. graph.edn --old`,
  `simpleviz extract .. graph-next.edn`, `simpleviz graph.edn next`;
  drop `old.png new.edn`), "Comparing two versions" rewritten around
  fork/suffix/promote, "Following refs" (works in a suffix comparison).
- `plugins/simpleviz/skills/simpleviz/SKILL.md`: the Running block and
  the compare paragraph; the ref rule loses "Not available in compare mode".
- `docs/development.md`: `bb serve graph.edn [suffix]`, `bb fork`,
  `bb promote`, the `file` parameter in suffix mode.
- Launcher usage text.

## Out of scope

- Refs inside an embedded-compare PNG.
- A UI for creating, listing or discarding forks.
- Rewriting refs in forks, or forking across roots.
