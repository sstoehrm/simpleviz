# Chord tree and grouped toolbar — design

Chords are fixed two-key pairs today, and `n n` does three different
things depending on the selection. This turns the chord table into a
tree of any depth, adds creation variants under `n n …`, numbers a
selection's pairs (`f p 1`–`9`), and gives the toolbar a second,
grouped layout whose buttons pop out the tree — usable with the mouse
alone.

Figure: `.blend/specs/2026-10-10-chord-tree.edn` (serve it with
`simpleviz`).

## Goal

Select a box, press `n n b`, name it: a new empty box appears inside
the selected one. Or click the toolbar's layout toggle to "grouped",
click `new n ▸`, then `new element ▸`, then `box b` — same result,
no keyboard. Typing `n` in either layout opens the same pop-out, so
the keyboard path shows what comes next.

Out of scope: user-defined keymaps, keeping the old two-key `n n` as
an alternative keymap (it cannot coexist with `n n n` without a
timeout), server changes.

## Chord tree

"Next to the selection" means: in the box that directly contains the
selected node or box, or at top level when none does.

| Keys | nothing | node | box | edge |
| --- | --- | --- | --- | --- |
| `d d` | | delete | delete | delete |
| `e 1`–`e 4` | | | | direction → ← ↔ — |
| `c s` / `c t` | | | | change source / target |
| `a e` | | add edge | add edge | |
| `a b` | | add to box | add box member | |
| `a n` | | | add node member | |
| `n n n` | free node | connected node (selected → new), top level | node inside the box | |
| `n n b` | empty top-level box | | empty box inside the box | |
| `n n s` | | sibling node next to the selection, no edge | same | |
| `n n c` | | connected node (selected → new), next to the selection | same | |
| `n n i` | | incoming node (new → selected), next to the selection | same | |
| `n n e` | | | | split the edge (below) |
| `n b` | | wrap in a new box | wrap in a new box | |
| `r r` | | rename | rename | |
| `r n` | | | remove node member | |
| `r b` | | remove from box | | |
| `f r` | | follow ref | follow ref | follow ref |
| `f p` | | follow pair (below) | follow pair (below) | |
| `f m` | | open md | open md | |

Changes against today: `n n` becomes `n n n`; `c n` is removed (it
duplicated `n n` on a box); `n n b`, `n n s`, `n n c`, `n n i`,
`n n e` and `f p 1`–`9` are new. Every `n n …` creation opens the
existing name prompt (`name` or `name::type`) and selects the new
element once it lands, like `n n` does today.

### Split an edge (`n n e`)

Edge `[A B]` (file order) selected, name X entered:

1. `add-node X` (+ its name/type), placed next to A and B: in their
   box when both sit directly in the same box, else top level.
2. `retarget-edge` `[A B]`, end target → X. The edge keeps every
   attribute (`:name`, `:type`, `:direction`, …) and becomes `[A X]`.
3. `add-edge [X B]` with the original edge's `:direction` only.

All in one `/api/edit` batch, so one undo reverts it. An existing
`[A X]` or `[X B]` edge cannot happen — X is new.

### Follow pair (`f p`)

Counted over the selection's *working* pairs (no `:problem`), in the
inspector's order:

| Working pairs | `f p` |
| --- | --- |
| 0 | not offered |
| 1 | follows it (as today) |
| ≥ 2 | a group: `f p 1`…`f p 9` follow pair *n*; the pop-out labels each `file#id`; pairs past 9 appear in the pop-out without a key |

The inspector's pair rows show the number of each working pair, so the
key is visible there too. The "several pairs — pick one in the
inspector" flash is gone.

## Toolbar

### Layout toggle

A small toggle at the toolbar's right end switches between **flat**
and **grouped**. The choice is yours, saved in this browser
(localStorage, like the theme and layout menus); default flat.

- **Flat** — today's layout: one button per available action, each
  showing its full chord (`new node n n n`, `sibling n n s`, …).
  Edge direction stays an inline row.
- **Grouped** — one button per first key with at least one available
  action. A group with exactly one available action shows that action
  directly (`Delete d d`; `rename r r` on a node outside any box).
  Otherwise the button is `<group label> <key> ▸` and opens the
  pop-out. Edge direction stays an inline row here too (it shows the
  current direction).

Group labels: `n` new · `n n` new element · `a` add · `r`
rename / remove · `c` change · `f` follow · `f p` follow pair. Order
of buttons follows the chord table.

### Pop-out

- One state for keys and clicks: `:chord` holds the pending key path
  (`[]` closed, `["n"]`, `["n" "n"]`). A key press or a click on a
  pop-out item appends to it; reaching an action runs it and clears
  the path; a key with no matching child clears it (as today).
- The pop-out sits above the toolbar and lists the children of the
  current path: actions as `<label> <key>`, subgroups as
  `<label> <key> ▸`. It appears in both layouts whenever the path is
  non-empty, replacing today's text hint (`n … n new node · …`).
- A group whose only child is a subgroup is skipped: the pop-out lists
  the subgroup's children directly, each with the keys still to type.
  Example, nothing selected: `new n ▸` opens a pop-out listing
  `node n n` and `box n b` (after the `n` already typed or clicked).
  Clicking `node` runs `n n n`; typing still needs every key.
- Availability uses `action-spec` (nil hides an action), so the
  pop-out never offers what would do nothing; empty groups are not
  shown.
- Esc, a click on the canvas, a selection change or a navigation
  closes it.

## Code

- `editor.cljs` (pure, tested):
  - `chord-table` entries become `[["n" "n" "b"] {kind [action label]}]`;
    a `chord-groups` map labels prefixes.
  - `chord-action [kind path pairs]` → the action when `path` is a complete
    chord; `chord-prefix? [kind path pairs]` → true when it is a group.
  - `chord-for [kind action]` → `"n n b"`.
  - `chord-menu [kind path available? pairs]` → the pop-out items:
    `{:keys :label :action | :group}` (`:keys` = the keys still to
    type), with chain-skipping and availability filtering.
    `available?` is a predicate over actions (app.cljs passes one
    built on `action-spec`); `pairs` are the selection's working
    pairs, which decide whether `f p` is an action (1) or a group
    (≥ 2, action `["follow-pair" n]`). `chord-action` and
    `chord-prefix?` take the same `pairs`.
  - `chord-hint` goes (the pop-out replaces it).
  - `creation-ops` gains `:for` kinds `"box"` (top-level empty box),
    `"box-inbox"`, `"sibling"`, `"connect-here"`, `"incoming"`,
    `"split"`; the entry carries the parent box (from `:parent-of`)
    and, for a split, the edge and its direction.
- `app.cljs`: `:chord` as a vector; key handler extends it; new
  actions in `action-spec`; `action-bar` renders flat or grouped;
  pop-out component; toggle + localStorage; numbered pair rows.
- `server/`: unchanged; all ops exist (`add-node`, `add-box`,
  `add-edge`, `box-add`, `retarget-edge`).
- Docs: the chord table in `docs/guide.md`, the help panel text in
  `app.cljs`, the Editing section of
  `plugins/simpleviz/skills/simpleviz/SKILL.md`.

## Testing

- `editor_test.cljs`: `chord-action`/`chord-prefix?`/`chord-for` for
  every row of the table, incl. removed `c n` and `n n` now being a
  prefix; `chord-menu` for nothing / node / box / edge, chain
  skipping, availability filtering, `f p` with 1 and 3 pairs;
  `creation-ops` for each new kind, incl. placement in the parent box
  and the split's three ops.
- Server: an `edit_test.clj` case applying the split batch to a file
  (retarget keeps attrs, new edge has the direction only) — guards the
  op composition against server changes.
- Browser check (`run` skill): both layouts, keyboard and click paths
  for `n n b` and `n n e`, `f p 2`.
