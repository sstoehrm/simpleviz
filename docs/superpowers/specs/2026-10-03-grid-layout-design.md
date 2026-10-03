# Grid layout — design

Boxes are the skeleton of most diagrams. A top-level box can name its
cell in a grid; the gridded boxes are placed exactly there, ELK lays out
what is inside each box, the rest of the graph is placed around them,
and edges between cells run through the gaps. The result should feel
arranged by hand rather than computed.

Figure: `.blend/specs/2026-10-03-grid-layout.edn` (serve it with
`simpleviz`).

## Goal

    {:boxes {:frontend {:grid [0 0] :components #{:web}}
             :backend  {:grid [1 0] :components #{:api :auth}}
             :data     {:grid [0 1 2 1] :components #{:db :cache}}}
     :nodes {:user {} :web {} :api {} :auth {} :db {} :cache {}}
     :edges {[:user :web] {:direction :->} [:web :api] {:direction :->}
             [:api :db] {:direction :->} [:api :cache] {:direction :->}}}

    [User] → ┌frontend┐ → ┌backend──┐
             │ [Web]  │   │ [API]   │
             └────────┘   │ [Auth]  │
                          └─────────┘
             ┌data─────────────────┐
             │ [Postgres] [Redis]  │
             └─────────────────────┘

frontend sits in column 0, backend in column 1, data spans both columns
of row 1. User, in no box, sits left of frontend because its edge points
into it. A file without `:grid` lays out exactly as today.

Out of scope: dragging boxes to cells, per-cell alignment options,
grids inside boxes, grid cells for nodes.

## Format

    :grid [col row]          ; one cell
    :grid [col row w h]      ; spanning w columns and h rows

- On a **top-level box** only (a box no other box lists in
  `:components`). `col`, `row` are integers ≥ 0; `w`, `h` integers ≥ 1,
  default 1.
- Lenient validation, like the rest of `normalize`: each of these warns
  and the box is treated as having no cell —
  - `:grid` on a nested box: `box "x": :grid only applies to top-level boxes, ignored`
  - a malformed value: `box "x": :grid must be [col row] or [col row w h] (integers, col/row ≥ 0, w/h ≥ 1), ignored`
  - cells overlapping another box's: `box "y": :grid [1 0] overlaps box "x", ignored` — the box first by sorted name keeps its cells.
- The normalized box payload carries `:grid {:col :row :w :h}` for a
  box that keeps its cell, nil otherwise. `:grid` also stays in the
  box's attributes, so the inspector shows and edits it like any other.
- `simpleviz check` and `/api/errors` report the warnings.

## Behavior

Grid mode is on when at least one top-level box has a cell (after
validation). Otherwise the layout is ELK layered over the whole graph,
unchanged.

### Boxes

- Each top-level box is laid out by ELK on its own: contents, nested
  boxes and the edges among them, with today's options (layered, RIGHT,
  box padding, spacing). A collapsed or empty top-level box is a leaf
  of its node-style size, no ELK run.
- An edge that leaves a top-level box gets a port on that box's border,
  on the side facing the element at its other end:
  - the other end's cell lies entirely in columns to the right → EAST,
    entirely to the left → WEST;
  - otherwise (columns overlap) below → SOUTH, above → NORTH;
  - same cell (two loose elements in one stack) → EAST on both ends.
  The box's ELK run includes the port (`elk.port.side`,
  `portConstraints FIXED_SIDE`) and the edge segment from the inner
  element to the port, so ELK arranges the contents towards their
  exits and routes the inner part. (Verified: ELK lays out a box that is
  the only child of a dummy root with ports on its border, including
  edges from nested boxes to those ports.)
- An edge whose endpoint is the top-level box itself, or a leaf (a loose
  node, a collapsed box), attaches directly to that element's border on
  the chosen side; several edges on one side are spread evenly along it.

### Grid sizing

- Column `c` is as wide as the widest box whose span is only `c`; row
  `r` as tall as the tallest box spanning only `r`. A spanning box
  wider (taller) than the columns (rows) it spans grows the last one by
  the difference. A column or row nothing occupies is 0 wide but keeps
  its gaps, so `[0 0]` and `[2 0]` leave a visible empty column.
- Gaps between columns and between rows are 80 px, widened when the
  edges running through them need more lanes (see Routing). Around the
  grid runs a 30 px gap on every side — a routing channel too, so a
  single row or column can still be routed around — then the 20 px
  outer margin, as today.
- Each box sits at the top-left of its cell (span), at its own size.

### Loose elements

Top-level nodes and top-level boxes without a cell are loose.

- A loose element attaches to the gridded box it has the most edges to
  (an edge to anything inside the box counts for the box; ties go to
  the box first by sorted name). If most of those edges point into the
  box (the loose element is their source), it goes left of the box,
  otherwise right.
- A loose element with no edge to a gridded box attaches to the
  attached loose element it has the most edges to, same side as that
  one, repeated until nothing changes.
- The rest — elements with no path of edges to a gridded box, though
  they may have edges among themselves — form the strip: one ELK run
  over just them and their edges (today's layered options), placed as
  one extra cell under the grid, starting at column 0 and spanning all
  columns. Its edges are routed by ELK, like edges inside a box.
- The loose elements beside one box and side form a stack, top-down in
  file order, in an extra column inserted next to the box's span
  (left of its first column or right of its last), in the box's top
  row. The extra columns are ordinary grid columns from then on: sized
  by their stacks, separated by gaps, routed through.
- A loose box is laid out by ELK on its own like a gridded box (ports
  included).

### Routing between cells

- Every cross-cell edge runs from its source port (or border point),
  straight out into the adjacent gap, along the gaps, and straight
  into its target port: right angles only.
- The path through the gaps is the one with the fewest bends, then
  the shortest, on the graph whose vertices are the gap crossings and
  whose edges are the gap segments between them; ties break
  deterministically (same input, same path). Two ends on the same gap
  connect straight along it.
- Edges sharing a gap segment get separate lanes 10 px apart, centred
  in the gap, ordered by the position of their endpoints so lanes
  don't cross needlessly; a gap is widened to `lanes × 10 + 40` px when
  that exceeds 80. Sizing and routing repeat — place, route, widen the
  gaps — until the gaps stop changing, at most three times.
- The arrowheads and the dashed "removed" style are drawn as today from
  the edge's point list.
- An edge label sits on the edge's longest segment, centred, offset to
  the segment's side as ELK's inline labels are.
- Edges inside one box keep ELK's own routing.

### Everything else

- Collapsing a gridded box keeps it in its cell at node size; the grid
  shrinks around it.
- Stability: boxes stay in their cells across edits. Inside a box the
  seeded relayout keeps working: the previous layout's positions,
  taken relative to the box, seed that box's ELK run.
- Layout cache: the fingerprint covers every per-box ELK input plus the
  grid placement input; an equal fingerprint reuses the layout.
- Compare mode: the merged payload's boxes carry the new side's `:grid`
  (a removed box its old side's). Two boxes landing on one cell this
  way are resolved as in Format (the later by sorted name becomes
  loose); no extra warning.
- Big graphs (> 500 nodes) still open collapsed; the grid applies to
  the collapsed boxes.
- Export, theming, hit-testing and editing work on the same scene and
  need no change.

## Architecture

### Server: `server/graph.clj`

- After membership is resolved (`parent-of` known), `resolve-grids`
  validates `:grid` per box, applies the overlap rule, warns, and
  assocs `:grid {:col :row :w :h}` or nil on each normalized box.
- `server/diff.clj` needs no change: the union keeps the new side's
  box map (the old side's for a removed box), `:grid` included.

### Page: `src/simpleviz/grid.cljs` (new, pure)

The grid layout, DOM-free; ELK is passed in.

- Structure: `top-of`, `edge-ends`, `top-items`, `grid-cells` (cells,
  overlap-safe), `grid-mode?`, `attach-loose` (`{:attached {id {:anchor
  :side}} :strip [ids]}`).
- Geometry: `slots` (each element's column/row tracks: grid index i is
  track 3i+1, a stack left/right of it 3i / 3i+2), `port-side`,
  `tracks`, `base-gaps`, `track-sizes`, `axis` (sizes, positions, gaps
  per axis), `centres` (gap centre lines), `widen`.
- Routing: `route-edges` (gap entries → points and lane counts),
  `label-at`.
- `(^:async layout-grid graph elk-graph run-elk positions)` — the whole
  pipeline: one ELK run per placed box (`transform/element-run`, seeded
  from `positions` relative to the box), one for the strip, placement,
  routing; returns an ELK-shaped layout `{:width :height :children
  [...] :edges [...]}` whose edges carry absolute points (`:container
  "root"`), so `scene/build-scene` consumes it unchanged.

`transform.cljs` gains `element-run`: one top-level element with its
ports inside a dummy root (ELK takes no ports on a run's root).

`app.cljs` `relayout!` calls `grid/layout-grid` instead of one
`.layout` when `grid-mode?`, with the same caching (the fingerprint
also covers the cells), generation and stale-result rules.

## Docs

- SKILL.md: `:grid` in the data-format rules (top-level boxes, `[col
  row]`/`[col row w h]`, loose elements placed beside what they connect
  to), and the tip to grid the main boxes of a spec figure.
- `docs/guide.md`: an attribute line and a "Grid layout" section.
- README data-format example: one `:grid` comment.
- In-app help: one sentence in "Navigate".

## Testing

- `test/graph_test.clj`: `:grid` accepted in both forms; each warning
  (nested, malformed values, overlap — the first by sorted name keeps
  it); the payload's `:grid` map; untouched files have no `:grid`.
- `test/diff_test.clj`: the union's `:grid` comes from the new side,
  a removed box keeps its old one.
- `test/simpleviz/grid_test.cljs` (pure, fake per-element layouts of
  given sizes, no ELK): `grid-mode?`; `port-side` for each relation;
  column/row sizing incl. spans and empty columns; loose attachment
  (left/right by edge direction, chains, unconnected row, ties);
  routing (fewest bends, lanes don't collide, gap widening, labels on
  the longest segment); the output's shape matches what
  `scene/build-scene` reads.
- `test/simpleviz/layout_test.cljs`: real ELK on a small gridded graph:
  boxes land on their cells, rows align, every edge has points, ports
  sit on the expected sides.
- End-to-end: a gridded copy of `examples/demo.edn` served and
  screenshotted in both themes; collapse a gridded box; edit inside a
  box (contents stay put); compare mode with a moved `:grid`.
