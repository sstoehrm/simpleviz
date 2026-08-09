# Compare mode: cycle through changes via the legend

**Date:** 2026-08-09
**Status:** Approved

## Purpose

In compare mode, let the user jump through all added, modified, and removed
elements without hunting for them: each legend row becomes a cycle button.

## UI

- Each `#diff-legend` row renders its count: `+ added · 3`.
- Clicking a row jumps to that status's first element; repeated clicks
  advance and wrap. While cycling, the row shows the position: `2/3`.
- A row with zero stops renders dimmed (reduced opacity) and clicks are
  no-ops.
- Rows are `<button>`s; clicks must not bubble into canvas pan/selection
  (the legend is already excluded from pan-zoom).
- Single-file mode: no legend, nothing changes.

## Stops

- A stop is a visible scene item (node, box, or edge — not edge labels)
  whose `:diff` equals the row's status.
- Collapsed shells represent their hidden changes: `prune`'s roll-up
  changes from boolean `:diff-inside` to **the set of statuses hidden
  inside** (nil/absent when none). A shell is a stop in every status it
  hides, in addition to any `:diff` of its own.
- Canvas keeps painting the amber dot exactly when `:diff-inside` is
  non-nil.
- Cycle order = the scene's stable item order. Per-status cursors live in
  app state and reset whenever a new scene is installed (relayout, file
  reload, collapse/expand).

## Jump behavior

- The target becomes the current selection (inspector opens; for modified
  elements it already shows the old → new changes section).
- The viewport pans so the item's bbox center is centered. Zoom changes
  only when the item would render smaller than ~40px in its larger
  dimension: then zoom is raised to make it ~40px, capped at 1.0. Zoom is
  never reduced.
- Implemented as `canvas/center-on!` (sets `view`, requests paint).

## Structure

- `simpleviz.scene/diff-stops` (pure): scene items → `{status [items]}`,
  including `:diff-inside` shell expansion. Unit-tested in the JS suite.
- `app.cljs`: cursor state `{status idx}`, legend rows as buttons, click
  handler (advance cursor → select + `center-on!`).
- `prune.cljs`: `contents-changed?` → `contents-changed` returning a set
  (JS Set or array) of statuses; `collapse-boxes`/`collapse-scene` store
  it under `:diff-inside` (nil when empty). Existing tests updated,
  boolean-truthiness call sites audited (canvas dot, any others).
- Server untouched.

## Testing

- JS: `diff-stops` grouping + shell expansion; prune roll-up returns
  status sets (nil when none); existing prune/scene tests updated.
- Manual: cycle each status on the demo pair, wrap-around, dimmed empty
  row, collapsed-shell stop, zoom bump on small elements, cursor reset on
  file edit.

## Out of scope

- Keyboard shortcuts, prev buttons, global all-status cycling.
- Auto-expanding collapsed boxes on jump.
