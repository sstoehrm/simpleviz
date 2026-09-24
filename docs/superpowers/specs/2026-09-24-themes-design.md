# Themes — design

Twelve built-in color themes and a per-graph `:theme` key that picks one
or builds a custom theme on top of one.

Figure: `.blend/specs/2026-09-24-themes.edn` (serve it with `simpleviz`).

## Goal

A graph file decides how it looks. `{:theme :nord ...}` renders the graph
in the Nord palette for every viewer and in every PNG/SVG export;
`{:theme {:base :nord :accent "#b58900"}}` changes single colors of it.
Files without `:theme` look and behave exactly as today (the viewer's
☀/🌙 toggle).

## Format

    {:theme :nord ...}                                          ; a built-in, by name
    {:theme {:base :nord :bg "#fdf6e3" :accent "#b58900"} ...}  ; overrides on a base

- The name may be a keyword or a string. `:base` defaults to `:light`.
- Every key a built-in theme defines can be overridden. There are 34,
  in five groups:

| Group | Keys | Value |
|---|---|---|
| chrome (13) | `:bg :panel :panel-border :panel-divider :text :text-strong :text-muted :text-dim :hover :hover-plain :accent :on-accent :shadow` | color |
| painter (7) | `:node-fill :node-stroke :edge :arrow :sub :label :btn-fill` | color |
| diff (3) | `:diff-added :diff-modified :diff-removed` | color |
| state (4) | `:state-new :state-in-progress :state-blocked :state-done` | color |
| type colors (7) | `:node-saturation :node-lightness :box-saturation :box-lightness :neutral-node-lightness :neutral-box-lightness` (percent, 0–100) and `:box-fill-alpha` (0–1) | number |

- A **color** is a string: `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, or an
  `rgb()`/`rgba()`/`hsl()`/`hsla()` function. Named colors are not
  accepted: they would need a name table on the server for little gain.
- `:bg` is both the page background and the canvas background. `:accent`
  is both the UI accent and the canvas selection ring. `:sub` is the
  `(type)` line and the ref inner border, `:label` the edge-label text.
- **Type colors** keep today's scheme: each `:type` gets a stable hue
  (FNV-1a hash into 255 golden-angle slots). A theme sets only saturation
  and lightness, so a type keeps its hue in every theme. Node names are
  `hsl(hue node-saturation node-lightness)`; box borders and titles are
  `hsl(hue box-saturation box-lightness)`, and box fills the same color
  at `box-fill-alpha`. Untyped elements use `hsl(0 0% neutral-*-lightness)`.
- Pinning a color to a type (e.g. every `:db` red) is out of scope.

### Precedence

- A file with `:theme` wins. The ☀/🌙 toggle is hidden while it is shown.
  The viewer's stored toggle choice is kept and comes back for files
  without `:theme` (e.g. after following a ref).
- Compare mode shows the **new** side's theme.
- An exported PNG/SVG paints in the theme on screen. A PNG embeds the EDN,
  `:theme` included, so serving the PNG restores the theme.

### Validation (lenient, like the rest of `normalize`)

Each problem is one warning, and the rest of the theme still applies:

| Input | Warning | Result |
|---|---|---|
| unknown name, `:theme :foo` | `:theme: unknown theme "foo" (built-in: light, dark, print, …)`, listing all 12 `NAMES` | no file theme |
| neither name nor map, `:theme 3` | `:theme must be a theme name or a map, ignoring it` | no file theme |
| unknown `:base` | `:theme: unknown base "foo", using light` | base `light` |
| unknown key | `:theme: unknown key :foo, ignored` | key dropped |
| bad color | `:theme :bg: expected a color (#hex, rgb(), hsl()), ignored` | key dropped |
| bad number | `:theme :node-lightness: expected a number from 0 to 100, ignored` (`0 to 1` for `:box-fill-alpha`) | key dropped |

There is no contrast check on custom colors: legibility is the author's
call. Built-ins are tested (see Testing).

## Built-in themes

| Name | Kind | Source |
|---|---|---|
| `light` | light | today's light palette |
| `dark` | dark | today's dark palette |
| `print` | light | own: white, greys, no box fills, dark type colors |
| `high-contrast` | light | own: black on white, saturated type colors ≥ 4.5:1 |
| `blueprint` | dark | own: deep blue paper, pale cyan lines |
| `paper` | light | own: warm off-white, sepia ink |
| `solarized-light` | light | [Solarized](https://github.com/altercation/solarized), MIT, © 2011 Ethan Schoonover |
| `solarized-dark` | dark | Solarized |
| `nord` | dark | [Nord](https://github.com/nordtheme/nord), MIT, © 2016-present Sven Greb |
| `dracula` | dark | [Dracula](https://draculatheme.com), MIT, © 2023 Dracula Theme |
| `carbonfox` | dark | [nightfox.nvim](https://github.com/EdenEast/nightfox.nvim) carbonfox, MIT, © 2021 James Simpson |
| `one-dark` | dark | [Atom](https://github.com/atom/atom) One Dark, MIT, © GitHub Inc. |

Borrowed palettes are credited in `THIRD-PARTY-NOTICES.md`. The values
come from each upstream source (Solarized base03…green, Nord nord0…15,
the Dracula spec, carbonfox's `palette/carbonfox.lua`, and Atom's
`one-dark-syntax/styles/colors.less` converted to hex). Keys with no
upstream counterpart (hover tints, button fill) are derived from those
colors. The full table lives in `server/themes.cljc` and nowhere else.

`light` and `dark` keep today's colors, with two deliberate changes in
`dark`: the selection ring uses the dark accent `#60a5fa` (the canvas
hardcoded the light `#2563eb`), and untyped node names get lightness 65
(today 40, which is barely legible on the dark node fill).

## Architecture

### Shared data: `server/themes.cljc` (ns `themes`)

A plain-data namespace that both runtimes load. bb reads it from
`server/`; squint compiles it to `public/js/themes.mjs` once `"server"` is
added to `squint.edn` `:paths`. squint only compiles `.cljs`/`.cljc`, so
the server's `.clj` files are untouched. The bundle and the jar already
ship `server/` and `public/`, so packaging doesn't change. In squint,
keywords are strings, so the maps read as string-keyed objects.

- `NAMES`: the 12 names in documentation order (keywords).
- `KEY-KINDS`: key → `:color` | `:percent` | `:alpha`, in documentation
  order.
- `CSS-KEYS`: the keys the page mirrors into CSS custom properties (chrome
  and diff, 16 in all), each as `--<key>`.
- `THEMES`: name → complete theme map (all 34 keys).

### Server

- `server/graph.clj` `normalize` reads `:theme`, validates it as above,
  and adds `:theme` to its result: the **resolved** map (base merged with
  valid overrides, all 34 keys), or no key when there is no valid file
  theme. The page then needs no merge logic. Validation is plain Clojure:
  a flat key → kind table gives specific messages more simply than a
  malli schema.
- `server/diff.clj` `union` carries the new side's `:theme`.
- `check`, `/api/errors` and the warning banner report theme warnings
  through `normalize`, with no further changes.
- `server/edit.clj` (rewrite-clj) leaves a `:theme` key untouched by
  edits. A test pins that.

### Page

- `colors.cljs`: `assign-indices` (type → table slot) is unchanged. The
  fixed `NODE-TABLE`/`BOX-TABLE`/`NEUTRAL-*` and `color-map` give way to
  `(tables theme)` → `{:node [255 colors] :box [255 {:border :fill}]
  :neutral-node c :neutral-box {:border :fill}}`, built from a theme's
  type-color keys.
- `scene.cljs`: node and box items carry `:color-idx` (the type's slot,
  or nil for untyped) instead of color strings. `build-scene`'s `:colors`
  is `{:node {type idx} :box {type idx}}`. Colors are chosen at paint
  time, so a theme change is a repaint: cached scenes in `layout-cache`
  stay valid.
- `canvas.cljs`: the palette atom holds the resolved theme plus its
  `tables`, set by `(set-theme! theme-map)`. `ACCENT`, the
  `light`/`dark` `palettes` map and the `type-color` lightness hack go
  away. Painters read node and box colors through the palette. New public
  `(box-border idx)` for the collapsed-boxes panel dot. `paint!` clears
  the canvas before filling `:bg`, so a translucent custom background
  doesn't pile up frame over frame.
- `app.cljs`:
  - The effective theme is the payload's `:theme` if present, else
    `(get themes/THEMES (:theme st))`, i.e. the toggle's `"light"` or
    `"dark"`.
  - `apply-theme!` takes a theme map. It sets each `CSS-KEYS` entry as a
    custom property on `document.documentElement.style`, calls
    `canvas/set-theme!` and requests a paint. It runs at startup (toggle
    theme), after each graph load, and on toggle, always *before* the
    state change that re-renders: rendering is synchronous, and the
    collapsed-panel dots read the palette. Re-applying is cheap (16
    properties, 510 table strings), so there is no identity check.
  - The ☀/🌙 button renders only when the payload has no `:theme`.
  - The `?` help panel gains one line on `:theme`.
- `public/style.css`: the `[data-theme="dark"]` block goes. `:root`
  keeps the light values as the pre-JS default. `data-theme` is no longer
  set.

## Docs

- `docs/guide.md`: a "Themes" section with both forms, the 12 names, the
  key groups, precedence and the validation rule. The Data format list
  links to it.
- `README.md`: one line naming `:theme` next to the data-format example.
- `plugins/simpleviz/skills/simpleviz/SKILL.md`: `:theme` in the rules
  list (names, the override form, and "the file's theme wins").
- `THIRD-PARTY-NOTICES.md`: the five borrowed palettes with their MIT
  notices.

## Testing

- `test/simpleviz/themes_test.cljs` (node): every theme in `NAMES` exists
  and defines exactly the `KEY-KINDS` keys with values of the right kind.
  **Legibility bar**: no built-in is less legible than today's `light`.
  The minimum WCAG contrast over all 255 hues is ≥ 2.75 for node-name
  colors on `:node-fill` and ≥ 1.8 for box titles on `:bg`. `:text` on
  `:panel` and `:label` on `:bg` are ≥ 4.5, and `high-contrast` type
  colors are ≥ 4.5.
- `test/simpleviz/colors_test.cljs`: `tables` builds 255 entries from a
  theme's saturation and lightness; the neutral colors and fill alpha
  follow the theme.
- `test/simpleviz/scene_test.cljs`: items carry `:color-idx` (nil when
  untyped).
- `test/graph_test.clj`: both forms resolve; each row of the validation
  table warns and degrades as stated; no `:theme` means no key.
- `test/diff_test.clj`: `union` takes the new side's theme.
- `test/edit_test.clj`: an edit keeps a file's `:theme` form.
- `test/docs_test.clj`: README/SKILL examples stay warning-free.
- In the browser: `dev/cdp.mjs` screenshots of `examples/demo.edn` under
  each built-in, a custom override, and compare mode; PNG and SVG exports
  match the canvas.
