# In-browser graph editor

Make the viewer an editor: edit attributes in the inspector, act on the
selected element with per-kind buttons, and in compare mode choose which
file receives the edits. The EDN file on disk stays the single source of
truth; the browser never renders unsaved state.

Spec figure: `.blend/specs/2026-08-15-editor.edn` (serve with
`simpleviz .blend/specs/2026-08-15-editor.edn`).

TODO: fold these components into a project concept graph (blend:deduce)
— deferred at spec approval; the repo has no concept graph yet.

## Decisions (agreed)

- **Persistence:** every edit writes back to the served `.edn` file
  through a rewrite-clj zipper patch — comments, key order, and
  hand-formatting survive. No browser-side graph mutation.
- **Edit scope:** full attribute CRUD in the inspector.
- **Undo:** per-file, in-memory snapshot stack on the server (capped at
  100 entries), Undo button + Ctrl+Z. No redo in v1.
- **Architecture:** semantic edit operations (approach A). The browser
  describes intent; the server owns patching, cascades, and validation.
- **v1 limits:** map-form files only (a pre-v2 vector-form file refuses
  edits with a clear error). PNG-served sessions are read-only — the
  edit UI is hidden entirely.

## Edit flow (the spine)

1. A UI action builds a **batch of ops** (pure `op-builder` fns) and
   `POST`s it to a new `/api/edit` endpoint as
   `{:file :old|:new :ops [...]}`; `:file` defaults to `:new` and is
   only user-selectable in compare mode.
2. The server snapshots the current file text onto the undo stack,
   applies all ops of the batch to one rewrite-clj zipper, and writes
   the file **once**. Any op failing fails the whole batch: no write,
   no snapshot retained, error JSON `{:error msg}` back to the browser
   (shown in the existing banner).
3. The mtime bump flows through the **existing** live-reload →
   normalize → render path. The editor adds no second rendering path;
   warnings, diff recomputation, and layout behave exactly as if the
   file had been edited in vim.

## Op catalog (server/edit.clj)

All ops address elements by id (nodes, boxes) or endpoint pair (edges).

| op | payload | effect |
|---|---|---|
| `set-attr` | target, attr, value | set/add one attribute |
| `del-attr` | target, attr | remove one attribute |
| `add-node` | id, attrs (optional) | new `:nodes` entry; error if id exists |
| `add-edge` | from, to, direction (optional) | new `:edges` entry; error if pair exists (either orientation) |
| `add-box` | id | new `:boxes` entry; error if id exists |
| `box-add` | box, member | append member (node or box id) to the box's `:components` |
| `retarget-edge` | edge [from to], end (`:source`/`:target`), to | rewrite the edge's map key |
| `set-direction` | edge, direction (`:->` `:<-` `:<->` `:-`) | set `:direction`; never swaps the key, keeping file diffs minimal |
| `delete` | target | remove element **with cascades** (below) |
| `undo` | — | pop the file's snapshot stack and restore (always sent as a single-op batch) |

Cascades (atomic, same write): deleting a **node** also deletes edges
touching it and removes it from any box's `:components`; deleting a
**box** removes the box, its id from other boxes' `:components`, and
edges endpointing it (members survive, unboxed); deleting an **edge**
removes only that entry.

Validation is semantic and happens against the current file: unknown
ids, duplicate ids, duplicate edge pairs, and vector-form files produce
named errors, not zipper failures.

## Inspector editing

Attribute rows become editable on click. Scalars edit in a text input —
the value is read as EDN; when parsing fails **or yields a symbol**,
the raw text is used as a string (typing `3` yields a number, `foo` the
string `"foo"`, `:kw` a keyword). Nested
collections edit in a raw-EDN textarea that must parse before save.
A trailing `+` row adds an attribute (key and value inputs); each row
has an `×` to delete. Enter or blur saves (`set-attr`/`del-attr`);
Escape reverts. `:name` and `:type` are ordinary attributes and need no
special casing. For edges, the `nodes`/`direction` pseudo-attrs stay
hidden as today — the action bar owns those.

## Selection actions

Buttons render in the details-panel header for the selected element:

- **Edge:** delete · change source · change target · direction
  segmented control (`→ ← ↔ —`).
- **Node:** delete · add connected node (inline id input; creates
  `add-node` + `add-edge` in one batch) · add to box (pick mode) ·
  new box around it (inline id input; `add-box` + `box-add` batch).
- **Box:** delete · add node (pick mode) · add box (pick mode).

**Pick mode** (change source/target, add-to-box, add-node/box-to-box):
a hint banner explains what to click, the cursor becomes a crosshair,
clicking a valid target sends the op, Escape or clicking empty canvas
cancels. Invalid targets (e.g. picking the box itself) are ignored with
the hint still up.

**Focus after layout:** creating a node stores its id as
`:pending-focus`; when the post-edit relayout lands, the app selects
and centers it (reusing the diff-legend `center-on!` mechanics), then
clears the pending id.

## Compare mode

A segmented **edit: old | new** toggle appears beside the diff legend
(compare mode only) and sets `:file` on every op batch. Both files
already live-reload, so an edit to either recomputes the union view
immediately. Undo stacks are per file. When one side is a PNG
(mixed compare), that side's toggle option is disabled.

## Errors and conflicts

Op errors never partially apply and never touch the file. If the file
changed on disk between render and edit, ops still validate against the
*current* text — a stale click on a since-deleted node returns "unknown
node", the same error path as any other. The pre-edit snapshot makes
even a surprising outcome undoable.

## Testing

- **Server (bb):** every op is text-in/text-out — exact-string
  assertions prove formatting and comments survive; cascade cases,
  batch atomicity, each named error, undo stack behavior (cap,
  per-file), `/api/edit` handler including read-only refusal.
- **Frontend (node):** op-builder payloads per UI action, pick-mode
  state transitions, inspector EDN parse/fallback rules — all pure fns.
- **End-to-end:** serve a fixture, POST an edit, assert the file text
  and the re-fetched `/api/graph`.

## Implementation phases

1. **Edit spine + inspector:** `edit.clj` ops, `/api/edit`, undo stack,
   inspector CRUD. Usable editor for attributes.
2. **Selection actions:** action bar, pick modes, id inputs, cascading
   deletes, focus-after-layout.
3. **Compare editing:** old/new toggle, per-file undo, mixed PNG
   handling, docs (README + skill).
