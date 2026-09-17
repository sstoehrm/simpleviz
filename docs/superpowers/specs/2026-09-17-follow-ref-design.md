# Follow refs between graphs — design

Issue #77: link other graphs and jump into them based on relative paths.

Figure: `.blend/specs/2026-09-17-follow-ref.edn` (serve it with `simpleviz`).

## Goal

A graph element may carry a `:ref` attribute naming another graph file by
a relative path. The page can follow it in place, and a trail of the files
visited lets the user click back to any earlier graph.

## Format

- `:ref` is a string attribute on a node, box or edge: a relative path,
  resolved against the directory of the file that contains it.
- `..` segments are allowed as long as the result stays below the **root
  folder**: the directory of the file the server was started with. A ref
  that resolves outside it is refused.
- Absolute paths are refused.
- The target must be an `.edn` file or an exported `.png`; a PNG target is
  read-only, as today.
- Any other value of `:ref` (non-string, empty) is an ordinary attribute:
  shown in the inspector, no follow action.

Example (`root.edn` and `sub/api.edn` in the same served folder):

    {:nodes {:api {:name "API" :ref "sub/api.edn"}}}

## Server (`server/serve.clj`)

- **Root folder**: the parent directory of the single served file,
  canonicalized at startup. Unchanged for compare mode, where the feature
  is disabled.
- **`resolve-path`** — `(resolve-path root-dir rel)` returns the canonical
  `java.io.File` for the root-relative path `rel`, or throws an
  `ex-info` whose message names the problem (the routes catch it and
  fold the message into their error payload) when:
  `rel` is absolute; the canonical result is not below `root-dir`
  (checked on the canonical path, so symlinks escaping the root are
  refused too); the extension is not `.edn`/`.png`; the file does not
  exist or is not a regular file. The error message names the offending
  path. All request handling goes through it; nothing trusts the page's
  own resolution.
- **Routes**, single-file mode only:
  - `GET /api/graph?file=<rel>` serves that file instead of the root file.
    The payload gains `:path` — the root-relative path of the file served
    (for the root file, its basename). `:file` (export download name)
    stays the basename. A `resolve-path` failure is returned as
    `{"error": msg}` in the payload, like a parse error, so the page shows
    it in the banner.
  - `GET /api/version?file=<rel>` returns that file's mtime. A refused
    path reports `{"mtime": 0}`: a constant, so the page reloads once
    (and shows the graph route's error) rather than polling in a loop or
    flagging the server as disconnected.
  - `GET /api/source?file=<rel>` returns that file's EDN text (or the
    embedded EDN of a PNG); a refused path is a 404 like today.
  - `POST /api/edit` body may carry `"path": <rel>`; when present, ops
    (including `undo`) apply to that file. `"file"` keeps its meaning
    (`"old"`/`"new"`) and must be `"new"` when `path` is given. Undo stacks
    are already keyed by absolute path, so each file has its own.
  - In compare mode, or with an embedded-old PNG root, any `file`/`path`
    parameter is refused with an error (`"refs are not available in
    compare mode"`).
- Path parameters are read from the query string with URL decoding.

## Page (`src/simpleviz/app.cljs`, pure parts in `src/simpleviz/editor.cljs`)

### URL state

- `?file=<rel>` — the graph shown, root-relative; absent means the root
  file.
- `&trail=<rel>,<rel>,...` — the files visited before the current one,
  root first, comma-separated (paths are URL-encoded; a comma in a file
  name is therefore safe). Absent means empty.
- On load the page parses both. `reload!` fetches `/api/graph` with the
  `file` parameter when set; `tick` polls `/api/version` the same way;
  export fetches `/api/source` the same way; edits carry `path`.
- `popstate` re-parses the URL and reloads, so browser back/forward walk
  the trail.

### Pure helpers (`editor.cljs`, tested)

- `(resolve-ref current-path ref)` → the root-relative target path with
  `.` and `..` collapsed, or `nil` when the ref is absolute, empty, or
  would climb above the root (more `..` than there are directories in
  `current-path`). `current-path` is the root-relative path of the file
  containing the ref (the root file's basename for the root).
- `(follow-url current-path trail target)` → the query string for the
  new page state: `file=target`, `trail=` the old trail plus
  `current-path`.
- `(crumb-url trail i)` → the query string that returns to crumb `i`:
  `file=` that path, `trail=` the crumbs before it.
- `(parse-nav query-string)` → `{:file rel-or-nil :trail [..]}`.
- `(ref-of sel)` → the string `:ref` of a selection payload, or nil.

### Follow ref

- Toolbar action `follow-ref`, label "follow ref", chord `f r`, available
  for a selected node, box or edge whose attrs hold a string `:ref`, and
  only when the graph payload is single-file (no `:old` side).
- Following: `resolve-ref` on the current path and the ref. `nil` →
  error banner `ref "<ref>" leaves the served folder`, nothing else
  changes. Otherwise `history.pushState` to the `follow-url`, reset the
  per-file page state — selection, editing, pick, chord, collapsed
  boxes, layout cache, pending focus, last mtime — and `reload!`.
- A target the server refuses (missing, wrong type) shows the server's
  error in the banner; the trail stays, so the user can go back.

### Trail bar

- Rendered when the trail is non-empty: a fixed bar at the top center
  (the compare legend's spot; the two never coexist). One crumb per
  trail entry plus the current file, separated by `›`. Crumbs show the
  root-relative path; the current file is plain text, the others are
  buttons.
- Clicking crumb `i` pushes `crumb-url` and reloads with the same
  per-file reset as a follow.
- The bar also appears when the page loads with a `file` parameter and a
  non-empty trail (bookmark or reload).

### Help and hints

- The toolbar button shows the `f r` hint like every other action.
- The in-app help "Edit" and "Keys" sections mention `:ref`, the button
  and the chord.

## Errors

| Case | Behaviour |
|---|---|
| ref climbs above the root (page-side) | error banner, stay |
| ref escapes the root after canonicalization, wrong extension, missing (server-side) | `{"error": ...}` payload → banner, trail intact |
| `file` parameter in compare mode | error payload |
| `path` in an edit body in compare mode | `{"error": ...}` edit response |
| ref not a string | no button, ordinary attribute |

## Testing

- `test/simpleviz/editor_test.cljs`: `resolve-ref` (plain, nested, `..`
  within root, `..` escaping → nil, absolute → nil, empty → nil,
  `./x.edn`), `follow-url`, `crumb-url`, `parse-nav` round trips with
  URL-encoded names, `ref-of`, chord `f r` in the chord table.
- `test/server_test.clj`: `resolve-path` (below root ok, `..` inside ok,
  escape refused, absolute refused, `.txt` refused, missing refused);
  `/api/graph?file=` serves the sub file and carries `:path`;
  `/api/version?file=`; `/api/source?file=`; edit with `path` writes the
  sub file and undoes it; `file` in compare mode refused; escape attempt
  through the route refused.
- Headless Chromium (manual, scripted with the CDP driver used for #74):
  serve a two-file fixture, select the node with the ref, press `f r`,
  assert the URL and the trail bar, click the root crumb, assert the
  root graph is back.

## Docs

- README: `:ref` in the data format block; "Following refs" paragraph in
  the Editing section (button, chord, trail, root-folder rule, compare
  mode off); chord table row `f r`.
- `plugins/simpleviz/skills/simpleviz/SKILL.md`: `:ref` in the format
  rules and the follow action in the editing paragraph.
- `docs/development.md`: the `file` parameter on the API routes.

## Out of scope

- Refs inside compare mode.
- Bundling referenced files into a PNG export; an exported graph keeps
  its `:ref` strings but they dangle when served from elsewhere.
- Creating or editing refs through a dedicated UI; they are ordinary
  attributes in the inspector.

## TODO

- Fold the follow-ref components (URL state, trail bar, follow action, resolver, server file parameter) into the project concept graph (blend:deduce) — skipped at spec approval on 2026-09-17.
