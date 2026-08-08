# Graph compare mode (diff overlay)

**Date:** 2026-08-08
**Status:** Approved

## Purpose

Let simpleviz serve *two* EDN graph files and render one merged diff view, so
changes between two versions of an architecture are visible at a glance:
added, removed, and modified nodes, edges, and boxes.

## CLI

    bb serve old.edn new.edn [--port N]

A second positional file enables compare mode. The first file is the **old**
(base) version, the second the **new**. With one file, behavior is exactly
today's. `bb dev` passes arguments through unchanged.

## Server

- `serve.clj` stores both file paths.
- `/api/graph` in compare mode: `graph/normalize` each file (unchanged), then
  a new `graph/diff` merges the two normalized graphs into **one union graph**
  in the existing shape (`:nodes :edges :boxes :parent-of :warnings`), with
  diff annotations (below). Single-file mode calls `normalize` alone, no diff
  keys — the frontend renders zero diff UI.
- `/api/version` reports a combined stamp — the string concatenation of both
  files' mtimes (e.g. `"1723100000-1723100050"`) — so editing either file
  changes the value and triggers a reload; the client's inequality check
  stays unchanged.
- A parse error in either file produces the existing error banner, prefixed
  with the failing file's name. Warnings from both files are shown, prefixed
  with the file name.
- The compare-mode `/api/graph` response also carries the two file names for
  the frontend legend (e.g. `:compare {:old "old.edn" :new "new.edn"}`).

## Diff semantics

Matching is by the identifiers users write, never positional ids:

| Element | Match key |
|---------|-----------|
| node    | node key (id) |
| box     | box name |
| edge    | **unordered** endpoint pair |

Statuses per element:

- `added` — present only in new.
- `removed` — present only in old.
- `modified` — present in both, but attrs differ, or (edges) direction
  differs, or (nodes/boxes) box membership differs, or (boxes) resolved
  component set differs.
- `same` — present in both, no differences. Carries no annotation keys
  (absence of `:diff` means unchanged, keeping single-file responses and
  unchanged elements identical to today).

Elements with status `added`/`removed`/`modified` carry `:diff "<status>"`.
Modified elements also carry `:changed {"<attr>" {:old x :new y}}` covering
changed/added/removed attributes (membership and direction changes appear
here too, as pseudo-attributes). Flipping `[:a :b]` to `[:b :a]` or changing
`:direction` is therefore *modified*, not remove+add.

## Union graph structure

- Layout structure (box membership, nesting) follows the **new** file.
- Removed elements are placed where the old file had them: inside their old
  parent box if that box still exists in the union, otherwise top level.
- Removed edges are laid out between union nodes, so an edge to a deleted
  node still renders, attached to the ghosted node.
- Attrs shown for `same`/`modified`/`added` come from the new file; for
  `removed`, from the old file.

## Frontend

### Visual encoding

Type-based colors stay as-is (the app's identity system). Diff status layers
on top:

- **Nodes/boxes:** an extra outline ring in the status color — green for
  added, amber for modified. Removed: red dashed outline plus ghosting
  (reduced opacity).
- **Edges:** stroke takes the status color; removed edges also dashed.
- Unchanged elements render exactly as today.
- Status colors (green/red/amber) get light/dark theme variants alongside the
  existing theme palette in `canvas.cljs`.
- A small fixed legend, only in compare mode: `old.edn → new.edn` plus the
  three-color key.

### Collapsed-box roll-up

A collapsed box containing any changed descendant (node, edge endpoint, or
nested box) shows an amber dot on its header. Required because graphs over
500 nodes open with all top-level boxes collapsed — without roll-up a compare
of a big graph would show nothing. Roll-up status is computed client-side
when boxes are contracted (`prune.cljs`).

### Inspector

- Modified elements: a "changes" section in the details panel, one row per
  changed attribute rendered as `old → new`.
- Removed/added elements: labeled as such next to the kind line.

### Untouched

Pan/zoom, hit-testing, theme toggle, layout caching (keyed by collapsed set,
cleared on file change), and live-reload polling all work as-is.

## Testing

- `graph/diff` Clojure unit tests: matching rules (nodes/boxes/edges,
  unordered edge pairs), status assignment, `:changed` extraction, removed
  placement (old parent kept vs. gone), membership-change → modified.
- Server test: two-file CLI parsing, `/api/graph` compare response,
  `/api/version` reacting to either file, per-file error naming.
- JS tests: scene passes `:diff` through to items; collapsed-box roll-up.

## Out of scope (possible follow-ups)

- Comparing against a git revision (`--against HEAD`).
- Side-by-side or toggle views.
- Filtering the view to changed elements only.
