# PNG export, EDN embedding, and `simpleviz extract`

**Date:** 2026-08-10
**Status:** Approved (issue #31; shipped as two PRs)

## Purpose

Export the rendered diagram as a PNG from the viewer (PR A), then make
the exported image a reusable artifact by embedding the source EDN as
PNG metadata and adding a CLI command to get it back out (PR B).

## PR A — export button (no metadata)

- An "Export PNG" button in the viewer, next to the theme toggle
  (`#export-btn`, excluded from pan-zoom like its neighbors).
- Exports the WHOLE graph (not the viewport): the scene is painted into
  an offscreen canvas sized `scene width × height` at **2×** scale
  (capped so the larger pixel dimension stays ≤ 8000 — huge graphs
  degrade scale rather than exploding memory), on the current theme's
  background, using the existing painter with a neutral view transform.
  No selection ring; text always on.
- Compare mode exports exactly what the canvas shows (diff styling
  included).
- Download name: `<graph-file-basename>.png` (new file's basename in
  compare mode); the served file names are already in the graph payload
  in compare mode — single-file mode needs the server to include the
  file's basename in `/api/graph` (small server addition:
  `:file "demo.edn"`), used only for the download name.
- Implementation: `canvas/paint-into!` refactor — extract the item-loop
  from `paint!` so both the live canvas and the export path share it —
  plus `app/export-png!` (offscreen canvas → `toBlob` → object-URL
  download). Painter stays DOM-only; no unit tests, verified via
  compile + suites + manual.

## PR B — EDN embedding + `simpleviz extract`

### Embedding (frontend)

- The exported PNG gains an **iTXt** chunk (UTF-8 capable; tEXt is
  Latin-1 only) with keyword `simpleviz-edn`, uncompressed, containing
  the RAW text of the served EDN file.
- The raw text comes from a new endpoint `/api/source` (server returns
  the file contents verbatim, `text/plain`); the normalized JSON is NOT
  what gets embedded — round-trip fidelity means the user's own text.
  In compare mode `/api/source?which=old` / `?which=new` select the
  file; no parameter means the single/new file.
- Compare mode: TWO chunks, `simpleviz-edn-old` and `simpleviz-edn-new`.
- Chunk splicing in `simpleviz.png` (new pure cljs ns, testable in
  node): parse the PNG byte stream, insert the iTXt chunk(s) right
  after `IHDR`, recompute nothing else (each chunk carries its own
  CRC32 — implement CRC32 in the ns). Input/output `Uint8Array`.

### `simpleviz extract` (CLI, pure babashka)

- `simpleviz extract diagram.png` → prints the embedded EDN to stdout;
  `simpleviz extract diagram.png graph.edn` → writes the file (refusing
  to overwrite, like `init`).
- Compare-mode images: `--old` selects `simpleviz-edn-old`; default is
  `simpleviz-edn-new`, falling back to `simpleviz-edn`.
- Implementation: `server/png.clj` (bb): PNG signature check, chunk
  walk, iTXt parse (keyword\0 flags language\0 translated\0 text),
  returns the text. Launcher subcommand delegates to
  `bb -m`/task? — the launcher runs `bb extract` from `~/.simpleviz`
  (new bb task `extract` in the release bb.edn, backed by png.clj);
  requires bb (checked like serve).
- Errors: not a PNG, no simpleviz chunk, requested variant missing —
  clear one-line messages, exit 1.

### Round-trip test (the point of the feature)

JS test writes a minimal PNG (or fixture bytes) through
`simpleviz.png`; a bb test reads the same bytes back via `png.clj` —
via a shared fixture file committed under `test/fixtures/` OR generated
by the JS test into a temp path consumed by the clj test. Simplest
deterministic: a tiny checked-in 1×1 PNG fixture; JS test embeds EDN
into it and asserts chunk placement; clj test embeds nothing but reads
a second checked-in fixture WITH an embedded chunk produced once by the
JS path. Exact fixture strategy is the plan's call, but both directions
must be tested and `extract`'s output must byte-equal the input EDN.

## Docs (PR B)

README (export button + extract command), plugin skill (Running +
viewer sections; RED/GREEN probes), launcher usage text.

## Out of scope

- SVG export.
- CLI-side PNG rendering (needs node-canvas/headless browser — breaks
  the bb-only runtime promise).
- Loading a PNG by passing it to `simpleviz`/drag-drop (possible later:
  `simpleviz diagram.png` = extract + serve).
