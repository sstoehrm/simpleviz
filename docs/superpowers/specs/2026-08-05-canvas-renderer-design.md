# simpleviz: canvas renderer v2

Date: 2026-08-05
Status: approved
Origin: post-merge user feedback — (1) selecting a box makes everything inside
look selected ("highlighting too much"), (2) user wants canvas rendering
instead of SVG, (3) edge labels overlap; graph needs more stretch.

## Decisions (user-confirmed)

- Renderer moves from SVG to `<canvas>` 2D.
- Edge labels move into ELK (reserved space + returned positions) plus wider
  spacing constants.
- Selection model fixed: outline only the selected shape; box hit-target is
  its header strip + border band only (clicking empty box interior selects
  nothing).

## Architecture

Three-layer split replacing `src/simpleviz/render.cljs`:

1. **`src/simpleviz/scene.cljs` (pure).** `build-scene {:layout .. :graph ..
   :colors ..}` → `{:items [..] :width :height}` — a flat, back-to-front draw
   list with absolute coordinates: `:box` items (x/y/w/h, border/fill colors,
   name/type/attrs, title-h), `:edge` items (per-section point lists with
   container offset applied — pen-lifts preserved — plus flattened points,
   arrows, name/type/attrs), `:edge-label` items (from ELK label positions,
   container-offset), `:node` items (x/y/w/h, resolved text color,
   name/type/attrs). Replaces `walk-layout`. Fully testable under node.
2. **`src/simpleviz/hit.cljs` (pure).** `client->graph view point` (inverse
   view transform) and `hit-test scene point tol` → the hit item or nil.
   Priority: nodes (point-in-rect) → edges (distance to any section polyline
   ≤ tol) → boxes in reverse draw order (innermost first), where a box hit
   counts only in its header strip (top `title-h`) or a 4px border band —
   never the interior content area. Fully testable under node.
3. **`src/simpleviz/canvas.cljs` (DOM).** HiDPI painter: canvas sized to
   client rect × devicePixelRatio; one full repaint per dirty frame via
   `requestAnimationFrame`; transform = dpr × view (pan/zoom now repaints
   instead of setting an SVG attribute). Draws rounded rects, per-section
   edge paths, arrowhead triangles (computed from the end segments — canvas
   has no markers), node name/(type) text (same fonts as measurement), edge
   labels with a white halo (strokeText under fillText). Selection: the
   selected item's own shape gets a 2px accent outline — nothing else
   changes, box children unaffected. Also owns `measure`, the mutable `view`,
   fit-on-first-render, pan/zoom listeners (same `#canvas-wrap` +
   `#details/#banner` guards + drag/click suppression), and a window-resize
   handler.

`app.cljs` keeps the state atom + reagami for DOM chrome (banner, details
panel, the `<canvas>` element itself with a stable `:key` so reagami patches
rather than recreates it). Flow: state change → reagami render → `paint!`.
Click on canvas → `client->graph` → `hit-test` → selection payload (kind,
title, subtitle, attrs from the scene item).

## ELK labels & spacing

`transform.cljs`: each named/typed edge gets
`:labels [{:text "name (type)" :width (ceil (measure ..)) :height 14}]`;
root options add `"elk.edgeLabels.inline" "true"` and stretch spacing:
`nodeNodeBetweenLayers 50→80`, `nodeNode 30→45`, `edgeNode 20→30`, new
`"elk.spacing.edgeEdge" "20"`. ELK returns per-edge label x/y
(container-relative, offset in scene like sections).

## Deletions / edits

- Delete `src/simpleviz/render.cljs` (hiccup SVG renderer).
- `public/style.css`: drop SVG-only rules (.node-bg/.node-name/.box-*/
  .edge-*/.selectable/.selected); keep banner/details/wrap; canvas fills the
  wrap.
- README: rendering description updated (canvas).

## Tests

- `scene`: box origin accumulation, edge/label container offsets, pen-lift
  preservation (multi-section), color resolution incl. neutral fallbacks.
- `hit`: priority order, box header-strip/border-band-only hits, nested-box
  innermost-first, edge distance tolerance, inverse transform round-trip.
- `transform`: labels attached with measured width (and absent for unnamed
  edges), updated spacing/option constants.
- `layout` (integration): ELK returns label coordinates for a labeled edge.
- Painter: browser verification checklist (no DOM tests).

## Out of scope

- Hover effects, partial repaints/dirty-region optimization, offscreen
  canvas, HTML-in-Canvas APIs (still origin-trial only).
