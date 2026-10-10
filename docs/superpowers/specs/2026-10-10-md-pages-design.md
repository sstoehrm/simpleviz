# md pages — design

simpleviz serves a markdown file the way it serves a graph. An md file
links to graphs and to other md files with ordinary markdown links,
which keep working on GitHub and in any editor. The page shows the md
file in the existing plain-text editor, full window, with a `»` in a
right-hand gutter on every line that holds a followable link.

Figure: `.blend/specs/2026-10-10-md-pages.edn` (serve it with
`simpleviz`).

## Goal

    notes.md:
      # Payment service
      The flow is in [the graph](pay.edn).
      Auth: see [auth](auth.md) and [tokens][t].

      [t]: diagrams/tokens.edn

`simpleviz notes.md` opens `notes.md` in the editor, filling the
window. Lines 2, 3 and 5 carry a `»` at the right edge (line 3 a `»2`
menu). Clicking `»` on line 2 shows `pay.edn` as a graph, with the
trail `notes.md › pay.edn`; the crumb leads back. `simpleviz check
notes.md` reports links whose target is missing or leaves the folder.

Out of scope: markdown rendering or preview, following `http(s)`
links, a keyboard shortcut for following a link, recursive `check`,
md files as compare roots, forking or exporting md files.

## Terms

- **Page**: what the window shows for the current URL — a graph (the
  canvas, as today) or an md page.
- **md page**: the doc editor (`md-panel`) in page mode — full window,
  part of the trail.
- **Docked panel**: the doc editor opened from a graph's `:md-ref`, as
  today.
- **Followable link**: a link whose destination, after the steps in
  *Links*, is a relative path ending in `.md`, `.edn`, `.png` or
  `.svg` (case-insensitive).

## Links (`server/mdlinks.cljc`)

Shared by the server (`check`) and the page (gutter): squint already
compiles `server/` (as it does `themes.cljc`). Written as a character
scanner without regexes, so it behaves the same on both platforms; one
reader conditional turns a decoded code point into a string.

`(links text)` → a vector of `{:line n :label s :dest s :kind k}` in
text order:

- `:line` — 1-based; `\r\n` and lone `\r` count as one line break.
- `:kind` — `"inline"` (`[label](dest)`), `"image"`
  (`![alt](dest)`), `"ref"` (a reference use) or `"def"` (a
  reference definition).
- `:dest` — the destination after: `<…>` brackets removed, an optional
  title (`"…"`, `'…'`, `(…)`) dropped, backslash escapes resolved, a
  `#fragment` and `?query` cut off, and `%XX` sequences decoded as
  UTF-8 (a hand-written decoder; a malformed sequence leaves `:dest`
  undecoded).

Recognized:

- Inline links and images: `[label](dest)`, `[label](<dest with
  spaces> "title")`, `![alt](dest)`. Brackets in the label nest;
  `\[` and `\]` are escaped. The label may span lines; `:line` is the
  line of the opening `[`.
- Reference definitions: a line `[id]: dest` (up to three spaces of
  indent, optional title). Labels match case-insensitively with runs
  of whitespace collapsed; the first definition of a label wins.
- Reference uses: `[label][id]`, `[label][]` and `[label]` — the last
  two only when a definition for that label exists. A use takes the
  definition's `:dest`; a use without a definition is not a link.
- Not scanned: fenced code blocks (``` and ~~~, closed by a fence of
  the same character at least as long, or the end of the text) and code
  spans (backtick runs of equal length). Indented code blocks are
  scanned (telling them from list continuations needs a full parser).
- Not links: autolinks (`<http://…>`), raw URLs, HTML tags, and any
  link whose destination is empty (`[a]()`, `[a](<>)`).

`(followable? dest)` — true when `dest` is non-empty, has no URL
scheme (`[a-zA-Z][a-zA-Z0-9+.-]*:`), does not start with `/`, and ends
in `.md`, `.edn`, `.png` or `.svg` (case-insensitive). Everything else
gets no `»` and is ignored by `check`: it is valid markdown that
simpleviz just cannot open.

## Server

### Serving an md root (`server/serve.clj`)

- `start!`: when the root file ends in `.md` (case-insensitive), the
  startup check is `doc-state` on it — it must exist, be at most 1 MiB
  and be valid UTF-8 — instead of `sides`. The server prints `serving
  notes.md` as for a graph.
- `parse-args` (and `cli/check-input!`) refuse a suffix with an md root:
  `compare mode needs a graph file (.edn, .png or .svg)`.
- New `GET /api/root` → `{"path": "<root-relative name of the root
  file>"}` (`root-rel`). The page asks it when the URL has no `file`
  parameter, to learn whether the root is md or a graph. In an embedded
  compare it answers the root's name as well.
- `/api/graph`, `/api/version`, `/api/source`, `/api/edit`, compare,
  fork and pairs stay graph-only. A `file` parameter naming an `.md`
  file is refused there as today (`resolve-path` with `ref-extensions`).
  md pages use the existing `/api/text` and `/api/text/save`, unchanged.
- `/api/errors` for an md file — the root when it is md, or
  `?file=x.md` — answers `{"error": <read error or null>, "warnings":
  [...]}` with that file's link warnings (the lines `check` prints,
  resolved against the md file's folder inside the served folder), so
  an agent checks a served md file the way it checks a graph.

### `simpleviz check <file.md>` (`server/check.clj`)

- By extension: `.md` runs the link check, everything else the graph
  check as today. The file's folder counts as the served folder.
- An md file over 1 MiB or not UTF-8: `error: <message>`, exit 1.
- For each followable link, in order, at most one warning:
  - `line N: <dest> leaves the served folder` — `resolve-path` refuses
    it as an escape;
  - `line N: <dest> not found` — no regular file there.
  A `"def"` link and the `"ref"` uses that point at it are one link:
  reported once, at the definition's line.
- Not recursive: linked md files and graphs are not checked.
- Output and exit codes as for graphs: `ok` / exit 0, else the lines /
  exit 1.

### Other commands (`server/cli.clj`)

- `export`, `fork`, `promote` and `init` with an `.md` file refuse
  with one line, exit 1, e.g. `export needs a graph file (.edn, .png or
  .svg)`, `init writes graph files (.edn)`.
- The usage text names `.md` for serving and `check`.

## Page

### Current document

- `editor/doc-kind [path]` → `"md"` for a path ending in `.md`
  (case-insensitive), else `"graph"`.
- The page's current path is `(current-path st)`: the md page's
  `:path` in page mode, else `(:path (:graph st))`. The places that read
  `(:path (:graph st))` for navigation — trail, follow ref, follow
  pair, `open-md!`, export name — use it. Nothing else changes for
  graphs.
- `load-nav!` decides by kind: `file` in the URL → `doc-kind` of it;
  no `file` → `GET /api/root` once (kept for the session) and
  `doc-kind` of its path. Kind `"graph"` → today's path (`tick` →
  `/api/version` → `reload!`). Kind `"md"` → `open-page!`.

### md page

- `:md` gains `:page` — true for the md page, false for the docked
  panel. There is at most one `:md`.
- `open-page! [path]`: fetches `/api/text?path=`; on `{error}` the page
  shows it in the error banner with the trail intact; otherwise
  `:md` becomes `(adopt-doc {:path path :page true …} out)`, and
  `:graph`, `:scene` and `:layout` are nil.
- Layout in page mode: the panel fills the window below the top bar
  (the `.full` geometry without its z-index over the trail). Header:
  path, `●`, `new file`, Save — no `⤢`, no `×`. Escape does nothing to
  it.
- Hidden in page mode: canvas, selection toolbar, inspector, collapsed
  list, relayout, layout menu, export, undo, the loading spinner.
  Shown: trail, banners, theme menu (your theme; md has no `:theme`),
  help.
- `tick` in page mode runs `poll-md!` and sets `:disconnected` from
  whether that fetch failed, instead of the version check.
- The tab title is the md file's name (`notes.md`).
- Saving, conflicts, CRLF round trip, `beforeunload`, Ctrl/Cmd+S: as
  for the docked panel today.

### Following a link

`follow-link! [link]` from a `»` (page or docked panel). The target is
`(resolve-ref md-path dest)` — relative to the md file holding the
link, not to the graph shown.

| Target | Result |
|---|---|
| `nil` (climbs above the served folder) | error banner `link "<dest>" leaves the served folder`; nothing moves |
| `.md` | navigate; it opens as an md page — a missing file shows `new file` and is created on the first Save |
| `.edn` | navigate; in an editable session `/api/create` first (an empty graph, as follow ref does), then it opens as a graph |
| `.png` / `.svg` | navigate; a missing file shows the server's error, trail intact |

Navigating pushes `follow-url current-path trail target` — the current
page joins the trail, as with follow ref. A second follow while one is
waiting is dropped (`:following`, as today).

`:ref "notes.md"` on a graph element follows the same way: the target
opens as an md page. `/api/create` is not called for `.md` targets.
`:md-ref` keeps opening the docked panel.

### Leaving an md page or switching docs

- Before any in-page navigation away from an md page (`»`, a crumb),
  `save-if-dirty!` runs; when it fails the
  navigation does not happen and the panel shows the save's error.
- Browser back/forward (`popstate`): the URL has already moved. When an
  md page with unsaved changes is showing, the page runs
  `save-if-dirty!`; on failure it pushes the md page's URL again
  (`history.pushState`), keeps the md page and shows the error; on
  success it loads the new URL.
- Navigating from an md page to a graph sets `:md` nil after the save.
- `»` in the docked panel:
  - to a graph: the page navigates; the docked panel stays open (it is
    tied to its file, as today).
  - to an md file: the docked doc is saved if dirty (failure aborts),
    the docked panel closes, and the target opens as the md page.
- Navigating between graphs leaves the docked panel open, as today.

### Compare mode

- An md page reached inside a comparison edits the one md file (md
  files are never forked). Following an `.edn` link from it opens that
  graph's comparison, the rule `:ref` already follows.
- `?focus=` is ignored on md pages.

### The `»` gutter

- The panel body becomes a relative wrapper: textarea, then a 28 px
  gutter column on the right, in docked and page mode alike.
- **Mirror**: a hidden (`visibility: hidden`, absolutely positioned)
  `div` with the textarea's font, font size, line height, padding,
  `tab-size`, `white-space: pre-wrap`, `overflow-wrap: break-word`,
  and width equal to the textarea's `clientWidth` (so the scrollbar is
  excluded). It holds one block per logical line of the textarea text;
  an empty line holds a zero-width space. A line's marker top is its
  block's `offsetTop`.
- **Markers**: `editor/line-markers [links]` (pure) groups the
  followable links by `:line` → `[{:line n :links [..]}]`; a `"def"`
  and its `"ref"` uses each get a marker on their own line. A line with
  one link shows `»`, a line with several `»N`; clicking `»N` opens a
  small menu listing `label → dest`, each entry following its link. The
  marker's tooltip is `label → dest` (for one link). A link whose
  target climbs above the folder shows a dimmed marker whose tooltip
  says so; clicking it shows the same banner as following.
- **Updates**: the marker layer is translated by `-scrollTop` on the
  textarea's `scroll` event (no measuring). Measuring runs at most once
  per animation frame, after a text change, on a `ResizeObserver` hit
  on the textarea (window resize, dock ↔ page), and once on
  `document.fonts.ready`. Links are re-parsed 150 ms after the text
  stops changing (a 1 MiB doc takes about half a second to scan), and
  once right away when a doc is opened or taken from disk.
- The gutter is display only; the text never changes.

### Help

- In-app help: a paragraph on md pages — `simpleviz notes.md`, the
  `»` gutter, which links are followed, saving before leaving.

## Docs

- README: md pages in the overview, the link rules (followable
  extensions, relative to the md file, the served-folder rule) and
  `check notes.md`.
- `docs/guide.md`: an "md pages" section; `check` on md files.
- `plugins/simpleviz/skills/simpleviz/SKILL.md`: an md file can be the
  served entry point and link graphs and other md files with plain
  markdown links (`[text](sub/api.edn)`), same folder rules as `:ref`;
  `simpleviz check notes.md` reports broken links; the lock rule for
  docs applies to md pages as well.
- `docs/development.md`: `/api/root`, `/api/errors` for an md root.

## Testing

- **Parser parity**: `test/mdlinks_cases.cljc` holds the cases as data
  (`[text expected-links]`); `test/mdlinks_test.clj` (added to
  `test:clj`'s requires and run list) and
  `test/simpleviz/mdlinks_test.cljs` both run them. Cases: inline,
  angle-bracket destination with spaces, title forms, nested and
  escaped brackets, multi-line label, image, reference use in all three
  forms, definition with indent and title, case-insensitive labels,
  first definition wins, undefined reference, `#` and `?` cut, `%20`
  and multi-byte `%XX`, malformed `%`, backslash escapes, fenced code
  (both fence characters, unclosed fence), code spans, CRLF and CR line
  numbers, autolink and raw URL ignored. `followable?`: each extension
  in either case, scheme, absolute, other extension, empty.
- **Server** (`server_test.clj`, `check_test.clj`, `cli_test.clj`):
  an md root starts, an md root that is too large or not UTF-8 is
  refused at startup, a suffix with an md root is refused, `/api/root`,
  `/api/errors` for an md root and for `?file=sub/x.md`, `/api/graph?file=x.md` refused; `check`
  on md: clean, missing target, escaping target, def reported once,
  non-followable ignored, over-size and non-UTF-8 errors, exit codes;
  `export`/`fork`/`promote`/`init` refuse md.
- **Page logic** (`editor_test.cljs`): `doc-kind`; the follow target
  for each row of the *Following a link* table; `line-markers`
  (single, several on one line, def and use, non-followable dropped).
- **End to end** (`dev/cdp.mjs`, real browser): serve an md root;
  markers sit on the right lines, also after a wrapping resize and
  after scrolling; `»` to a graph and the crumb back; `»N` menu; edit
  then crumb (saved); edit then browser back (saved); a refused save on
  back keeps the md page; `»` from the docked panel to a graph (panel
  stays) and to an md file (becomes the page); `:ref` to an md file.
