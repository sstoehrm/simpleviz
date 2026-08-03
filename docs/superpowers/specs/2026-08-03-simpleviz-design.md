# simpleviz — EDN graph visualization tool

Date: 2026-08-03
Status: approved

## Purpose

A simple, local graph visualization tool. The user edits an EDN file describing
nodes, edges, and grouping boxes; a browser page renders the graph and live-updates
on file change. Clicking any element shows its full attributes in a details panel.

## Architecture

- **Server:** babashka (`bb serve <file.edn>`), using the http-kit server bundled
  with babashka. No dependencies outside babashka's built-ins.
  - `GET /` and static assets: `index.html`, `app.js` (+ ES modules), `style.css`,
    vendored `elk.bundled.js` (checked into the repo; no CDN, works offline).
  - `GET /api/graph`: reads the EDN file, parses it, returns JSON.
    Keywords become strings (`:->` → `"->"`, `:active` → `"active"`); sets become
    arrays. On parse failure returns `{"error": "<message>"}` with status 200 so
    the page can render the error.
  - `GET /api/version`: returns the file's last-modified time as JSON.
- **Frontend:** plain ES modules, no build step. Polls `/api/version` every 1 s;
  on change refetches `/api/graph` and re-renders, preserving current pan/zoom.
- All graph logic (validation, colors, layout input, rendering) lives in the
  browser. The server only reads, parses, converts, and serves.

## Data format (EDN)

```clojure
{:nodes {"name" {:name "name"        ; display name (falls back to the map key)
                 :type "service"     ; free-form; drives name color, shown as (type)
                 :role [:active]     ; any other attribute: hidden, details view only
                 }}
 :edges [{:nodes ["a" "b"]           ; VECTOR — order defines left/right
          :direction :->             ; :-> (a→b) | :<- (b→a) | :<-> | :-
          :name "calls"              ; edge label
          :type "http"               ; shown as (type) next to the name; no color role
          }]                         ; any other attribute: details view only
 :boxes [{:name "cluster-1"
          :type "zone"               ; drives box color (separate table)
          :components #{"a" "b"}     ; node names and/or other box names; boxes nest
          }]}                        ; any other attribute: details view only
```

Notes:

- Edge `:nodes` is a **vector**, not a set — sets are unordered so `:->`/`:<-`
  would be meaningless. `:->` points from first to second element; `:<-` reverses.
- `:direction` defaults to `:-` (undirected) when absent.
- Box `:components` may be a set or vector (order is irrelevant there).
- Edges connect nodes only (not boxes).

## Validation rules

Bad input never yields a blank page; problems render as a warning/error banner.

- EDN parse error → error banner with the parser message; last good render stays.
- Edge referencing an unknown node → edge skipped, warning listed.
- Box component referencing an unknown node/box → component ignored, warning.
- ELK requires a strict hierarchy: boxes must be disjoint or fully nested.
  If a component appears in two sibling boxes, the first box in file order wins;
  warning listed. A box containing itself (directly or via a cycle) → cycle
  broken at the repeated element, warning.
- Edge with `:nodes` not a 2-element vector → skipped, warning.

## Color assignment

Two fixed 255-entry tables, one for node types, one for box types.

- Table construction: entry *i* has hue `(i × 137.508°) mod 360` (golden angle),
  with fixed lightness/chroma per table:
  - Node table: saturated, dark enough for legible text on white.
  - Box table: muted/pale variant for borders and translucent fills.
- Assignment: FNV-1a hash of the type string → index `hash mod 255`. If the slot
  is already claimed by a *different* type, linearly probe to the next free index.
  Golden-angle ordering makes adjacent indices visually distinct, so probing one
  step already yields a clearly different color.
- Types are assigned in sorted order, making colors deterministic regardless of
  file order, and stable under unrelated additions (a new type only shifts an
  existing type's color if it collides and sorts earlier).
- Empty/missing type → fixed neutral gray, not from the table.

## Layout

- ELK.js (`elk.bundled.js`, vendored), algorithm `layered`, direction `RIGHT`.
- Boxes map to ELK compound nodes with top padding for their title.
- Node width/height estimated from label text length (canvas `measureText`).
- All edges are given to ELK as directed (source → target after normalizing
  `:<-` by swapping); direction variants only affect arrowhead rendering.

## Rendering & interaction

- SVG inside a full-window viewport `<g>` carrying the pan/zoom transform.
- Node: name text colored by its type's color, `(type)` in gray beneath.
- Edge: routed polyline/spline from ELK bend points; arrowheads per direction
  (`:->`/`:<-` one end, `:<->` both, `:-` none); label `name (type)` at midpoint.
- Box: rounded rect, border + translucent fill from box color table,
  `name (type)` top-left.
- Pan: drag background. Zoom: mouse wheel, centered on cursor.
- Click node/edge/box → right sidebar with **all** attributes of that element
  (including hidden ones), pretty-printed. Click background or ✕ closes it.
- Selected element gets a highlight outline.
- Warning banner (collapsible) at the top when validation produced warnings.

## Project layout

```
bb.edn                 ; tasks: serve, test
server/serve.clj       ; http server, EDN→JSON, version endpoint
public/index.html
public/style.css
public/app.js          ; entry: fetch, poll, orchestrate
public/lib/colors.mjs  ; tables + hashing/probing (pure)
public/lib/validate.mjs; schema checks, warnings (pure)
public/lib/transform.mjs; validated graph → ELK graph (pure)
public/lib/render.mjs  ; SVG rendering, pan/zoom, details panel
public/vendor/elk.bundled.js
examples/demo.edn
test/*.test.mjs        ; node --test for the pure modules
test/server_test.clj   ; bb test for EDN→JSON conversion
```

## Testing

- `node --test test/` covers colors (determinism, probing, distinctness),
  validation (each rule above), and transform (direction normalization,
  box nesting, ELK structure).
- `bb test` covers server EDN→JSON conversion incl. keyword/set handling and
  parse-error response.
- Manual verification via `examples/demo.edn` exercising every feature:
  nested boxes, all four directions, hidden attributes, and a color-collision
  pair of types. The demo renders cleanly; broken-input behavior is covered by
  the automated tests.

## Out of scope (YAGNI)

- Edges attached to boxes, edge bundling, manual node dragging, layout
  persistence, export (SVG/PNG), multiple files, force-directed mode.
  All addable later without redesign.
