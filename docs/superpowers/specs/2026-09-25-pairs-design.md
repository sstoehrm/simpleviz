# Pairs — design

A `:pair` links a node or box to the same thing shown in another graph
file: a different view of it. Following a pair opens that file with the
paired element selected; the other side knows about the pair even when
only one side declares it; and broken pairs are reported.

Issue: #102. Figure: `.blend/specs/2026-09-25-pairs.edn` (serve it with
`simpleviz`).

## Goal

`:ref` points at a whole other graph. A pair points at one element in
another graph, for several views of one system (say, a deployment view
and a runtime view that both show the API):

    ;; views/overview.edn
    {:nodes {:api {:name "API" :pair "deploy.edn#api-svc"}}}

    ;; views/deploy.edn
    {:nodes {:api-svc {:name "API service"}}}

Selecting API in the overview and following its pair opens `deploy.edn`
with `api-svc` selected and centered. In `deploy.edn`, `api-svc` shows
the pair too, pointing back, although only the overview declares it.
If `api-svc` is renamed or deleted, the overview's banner, `simpleviz
check` and `/api/errors` say so.

Out of scope, for a follow-up spec: keeping attributes of paired
elements in sync (one edit rewriting both files), and a cross-file
"add pair" pick mode. Pairs are created by writing the `:pair`
attribute, in the file or the inspector.

## Format

    :pair "views/deploy.edn#api-svc"                      ; one pair
    :pair ["views/deploy.edn#api-svc" "runtime.edn#api"]  ; several views

- `:pair` goes on a node or a box. Its value is a pair string or a
  vector of them.
- A pair string is a path, `#`, and a node or box id. The path follows
  the `:ref` rules: relative to the file the pair is in, `..` allowed,
  never above the folder of the served root file, never a fork
  (`x-next.edn` while comparing with `next`), and an `.edn` file or an
  exported `.png`.
- The id is a node or box id as written in the target file's keys
  (`:api-svc` and `"api-svc"` both appear as `api-svc`). When the target
  file has a node and a box with that id, the pair means the node, as an
  edge endpoint does.
- One side declaring a pair is enough; the other side learns about it
  from the reverse index (see Server). Both sides may declare it; the
  pair then shows once per declaration.

### Validation (lenient, like the rest of `normalize`)

Each problem is one warning; the element still renders, and a broken
pair still shows in the inspector with its problem.

Warnings follow the existing `node "id": …` / `box "id": …` style; paths
in them are root-relative.

| Case | Warning (for node `api` in `views/overview.edn`) |
| --- | --- |
| value isn't a string or a vector of strings | `node "api": :pair must be "file#id" or a vector of them` |
| no `#`, empty path or empty id | `node "api": pair "deploy.edn" needs a file and an id, as in "deploy.edn#api"` |
| bare `"#id"` (same file) | `node "api": pair "#x" needs a file; pairs within one file aren't supported` |
| `:pair` on an edge | `edge [a b]: :pair applies to nodes and boxes` |
| path above the served folder | `node "api": pair "../../x.edn#a": ../../x.edn leaves the served folder` |
| a fork | `node "api": pair "deploy-next.edn#a": views/deploy-next.edn is the fork of views/deploy.edn — pair with the original` |
| file missing | `node "api": pair "deploy.edn#api-svc": views/deploy.edn not found` |
| file doesn't parse | `node "api": pair "deploy.edn#api-svc": views/deploy.edn does not parse` |
| id missing | `node "api": pair "deploy.edn#api-svc": no node or box api-svc in views/deploy.edn` |

`simpleviz check` checks the outgoing pairs of the file it is given,
treating that file's folder as the served folder, as `simpleviz <file>`
would. It doesn't scan for incoming pairs.

## Behavior

- Following a pair never creates a missing file (a `:ref` does): an
  empty graph can't contain the element.
- In a suffix comparison, following a pair opens the target file's own
  comparison, with the element selected, as a ref opens the referenced
  file's comparison.
- A graph served from a PNG has no toolbar, so no "follow pair" button
  or `f p`, as with refs; its inspector rows still follow (they only
  navigate). PNG files aren't scanned for incoming pairs; a pair may
  still name an exported `.png` as its target file.

## Architecture

### Server: `server/pairs.clj` (ns `pairs`)

Pure functions plus one cache:

- `parse`: a `:pair` value → `[{:file :id :raw}]` and warnings for
  malformed values.
- Paths: a pair path is joined to the declaring file's folder by
  `paths/resolve-ref`, which is `fork/resolve-ref` moved into a leaf
  namespace (`pairs` must not require `fork` or `serve`: `serve` requires
  `pairs`, and `fork` requires both). Reading a target goes through the
  caller's `:read`, which in `serve` is `serve/resolve-path` (the
  canonical inside-the-served-folder check `?file=` uses) plus
  `parse-graph`; forks are recognized with `serve/fork-base`.
- `index`: the reverse index of a folder. Every `.edn` under the served
  root's folder, recursively, skipping dot-folders and `node_modules`
  and, with a comparison suffix active, fork files (`*-<suffix>.edn`). Each file is
  read with `serve/parse-graph` once per mtime and cached in an atom
  keyed by path; a file that fails to parse contributes nothing (its own
  check reports it). The index maps `"file#id"` of each target to the
  elements that pair into it: `[{:file :id :kind}]`.
- `attach`: given a normalized graph, its root-relative path and the
  index, adds `:pairs` to each node and box and appends the outgoing
  pairs' warnings to the graph's `:warnings`. Outgoing pairs are checked
  by reading their target files through the same mtime cache; incoming
  pairs come from the index.

`serve/graph-response-body` calls `attach` for the file it serves, after
`parse-graph`. In compare mode it attaches pairs to each side before
`diff/union`, so every element carries the pairs of the side it is shown
from (the new side unless it was removed), and incoming pairs are
looked up under the original's path.

Payload, on each node and box of `/api/graph`:

    :pairs [{:dir "out" :file "views/deploy.edn" :id "api-svc" :kind "node"
             :raw "deploy.edn#api-svc"}
            {:dir "in"  :file "views/runtime.edn" :id "api" :kind "box"}
            {:dir "out" :file "views/gone.edn" :id "x" :raw "gone.edn#x"
             :problem "views/gone.edn not found"}]

`:file` is root-relative (for `"out"` the resolved target, the raw path
when it doesn't resolve; for `"in"` the declaring file). For `"out"`
pairs, `:kind` is the target's; for `"in"` pairs, it's the declaring
element's. `"in"` pairs never carry a `:problem`.

`check/check` runs `attach` with an empty index and the checked file's
folder as the served folder: it checks outgoing pairs only.

`fork/ref-targets` becomes the targets of refs and pairs, so `fork` and
`promote` carry pair target files along like ref targets.

Cost: the first scan parses every `.edn` in the folder (`big-5k.edn`,
1.3 MB, parses in about 100 ms); afterwards only files whose mtime
changed are parsed again.

### Page

- `scene`: items of paired nodes and boxes carry `:pair?` (any pair) and
  `:pair-problem?` (an outgoing pair with a problem).
- `canvas`: a small ⇄ mark on the bottom-left corner of such items,
  drawn in the warning color when `:pair-problem?`. `:state` keeps the
  top-right corner, `:ref` its double border.
- `details-view`: a "Pairs" section above the attributes, one row per
  pair: `→ views/deploy.edn#api-svc` (declared here) or
  `← views/runtime.edn#api (points here)`. A broken row is dimmed and
  shows its problem. Clicking a row follows it.
- Toolbar: "follow pair" (`f p`) when the selection has exactly one
  working pair. With several, `f p` shows the hint `several pairs —
  pick one in the inspector`. The chord goes into `editor/chord-table`.
- Navigation: following a pair navigates like following a ref (the
  current file joins the trail) with `focus=<scene id>` added to the
  URL (`n:api-svc`, `b:…`); `editor/parse-nav` and `nav-query` carry
  it. After the target loads, the element is selected through the
  existing `:pending-focus` path and centered with `canvas/center-on!`.
  Because the focus is in the URL, back, forward and reload land on the
  element too. A focus naming no element shows the banner `no node or
  box api-svc in views/deploy.edn`, with the trail intact.
- The page polls only its own file, as today. Pairs added or removed in
  other files show after this file's next reload or after navigating.

## Docs

- `docs/guide.md`: a "Pairs" section after "Following refs", the `:pair`
  line in the data-format list, and `f p` in the chord table.
- `README.md` and the plugin `SKILL.md`: a `:pair` line in the
  data-format rules.
- The in-app help: `f p` and a sentence on pairs.

## Testing

- `pairs` unit tests: parse (every warning in the table), path
  resolution (`..`, the folder boundary, forks), index (dot-folders
  skipped, forks skipped with a suffix, unparseable files skipped, the
  mtime cache re-reading only a changed file), attach (both directions,
  node wins over box, problems into `:warnings`).
- `server_test`: `/api/graph` and `/api/errors` for a folder with a pair
  in each direction and a broken one; compare mode (the new side's
  pairs, incoming pairs under the original's path); `check` on a file
  with a broken pair; `fork` copying a pair target.
- Page (squint tests): `parse-nav`/`nav-query` round-trip `focus`; the
  `f p` chord; scene items carry `:pair?`/`:pair-problem?`.
- In the browser: follow a pair both ways, back via the trail and the
  back button, a broken pair's banner and dimmed row.

TODO: fold pairs into the project's concept graph (`blend:deduce`); skipped for now.
