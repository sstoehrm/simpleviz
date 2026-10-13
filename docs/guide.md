# Guide

The details the README leaves out. In the page, `?` opens a short version.

## Data format

The README example shows every attribute simpleviz reads.

- Identifiers may be keywords or strings.
- `:name` defaults to the key. `:type` colors a node's name or a box (boxes
  have their own palette), and a type keeps its color across restarts and
  edits. `"component"` is always blue, so it stands apart from `"service"`. Any other attribute shows only in the inspector.
- A node of `:type "database"` (in any case) is drawn as the database
  symbol, a cylinder.
- `:text` on a node or box shows inside it: under a node's name and type,
  under a box's header (not while the box is collapsed). A string breaks at
  `\n`; a vector of strings gives one line each. Lines wrap at about 220 px,
  and the element grows to fit.
- `:state` — `:new`, `:in-progress`, `:blocked` or `:done` — puts a mark on
  the node's top-right corner: grey disc, blue half disc, red square, green
  disc with a check. Any other value is an ordinary attribute.
- `:ref` links the element to another graph file (see
  [Following refs](#following-refs)). A node with a ref gets a double border.
- `:md-ref` names a markdown doc that describes a node or box (a spec, an
  ADR, notes), as a path relative to the file, `.md` only. It draws a
  dotted border, and "open md" (`f m`) edits the doc (see
  [Editing docs](#editing-docs)).
- `:pair` links a node or box to the same thing in another graph file
  (see [Pairs](#pairs)). A paired element gets a ⇄ mark.
- `:pos [col row]` places a top-level node or box by hand, on a grid of
  20 px cells (see [Manual layout](#manual-layout)). The page writes it
  when you drag.
- `:theme` at the top level sets the graph's colors (see [Themes](#themes)).
- An edge's key is its endpoints, nodes or boxes, in left/right order.
  Writing both `[:a :b]` and `[:b :a]` warns "same connection". An edge
  between a box and its own content, or a box and itself, is skipped with a
  warning. A name that is both a node and a box resolves to the node.
- The pre-v2 vector forms (`:edges [{:nodes [..] ..}]`,
  `:boxes [{:name ".." ..}]`) still render but can't be edited in the page.

Validation runs server-side with [malli](https://github.com/metosin/malli).
Invalid references, duplicate box memberships and containment cycles skip the
element and explain why in a warning banner. A parse error shows an error
banner and keeps the last good render. Graphs with 500+ nodes open with all
top-level boxes collapsed.

## Themes

A graph file can set its own colors with a top-level `:theme`: either the
name of a built-in theme or a map of changes on top of one.

    {:theme :nord
     :nodes {:api {:type "service"}}}

    {:theme {:base :nord          ; the theme to start from (default :light)
             :bg "#fdf6e3"        ; then any theme key
             :accent "#b58900"}
     :nodes {:api {:type "service"}}}

The file's theme carries into PNG and SVG exports. A comparison shows the
new file's theme.

The theme menu at the top of the page picks your theme for every graph
without `:theme`. It's kept in `~/.simpleviz/prefs.edn`, not in any graph
file, so every page and every run of `simpleviz` shows it, and
"default" follows the operating system's light or dark setting. A file's
`:theme` wins over it: the menu then shows the file's theme, marked
"(file)", and is disabled; change that theme in the file.

Built-in themes: `light` and `dark` (the two the OS setting picks from),
`print` (white, greys, no box fills), `high-contrast`, `blueprint`, `paper`,
`solarized-light`, `solarized-dark`, `nord`, `dracula`, `carbonfox`,
`one-dark`, `tabak-light` and `tabak-dark`.

| Group | Keys |
|---|---|
| page | `:bg :panel :panel-border :panel-divider :text :text-strong :text-muted :text-dim :hover :hover-plain :accent :on-accent :shadow` |
| canvas | `:node-fill :node-stroke :edge :arrow :sub` (the `(type)` line) `:label` (edge labels) `:btn-fill` (box header button) |
| compare | `:diff-added :diff-modified :diff-removed` |
| `:state` marks | `:state-new :state-in-progress :state-blocked :state-done` |
| type colors | `:node-saturation :node-lightness :box-saturation :box-lightness :neutral-node-lightness :neutral-box-lightness` (0–100), `:box-fill-alpha` (0–1) |

(`:on-accent`: text on accent-colored buttons and hints)

Colors are strings: `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, or
`rgb()`/`rgba()`/`hsl()`/`hsla()`. Named colors aren't accepted. Each `:type` keeps its hue in every theme: the type-color keys
set only saturation and lightness, and the `neutral` ones the grey of
untyped nodes and boxes. An unknown theme, key or value is skipped with a
warning and the rest of the theme applies. Custom colors get no contrast
check.

## Comparing two versions

A comparison is a graph against a *fork* of itself, named by a suffix:

    simpleviz fork graph.edn next      # graph-next.edn, plus a fork of every file graph.edn refs
    simpleviz graph.edn next           # compare graph.edn (old) → graph-next.edn (new)
    simpleviz promote graph.edn next   # each fork replaces its original

`fork` copies the file, and every file reachable through `:ref`, to
`<name>-<suffix>.<ext>` siblings. Refs inside the copies stay as they are.
`promote` walks the fork's refs and moves every fork it finds over its
original. Forks outside that closure are left alone.

Both files render as one merged diagram. Added elements get a green `+` ring
and modified ones an amber `~` ring (select one for an old → new attribute
list). Removed ones stay visible as red, dashed ghosts. Nodes and boxes match
by key, edges by endpoints; flipping the pair or changing `:direction` counts
as modified. The layout follows the new file, and removed elements keep their
old place. A collapsed box that hides a change shows an amber dot. The legend
at the top counts the changes per status (click a row to step through them)
and has the old|new toggle that picks which file edits go to. Both files
live-reload.

Following a ref opens the referenced file's own comparison. A referenced
file without a fork shows as unchanged, and the first edit to its new side
creates the fork. A fork without an original shows everything as added, and
the first edit to its old side creates the original. The file the server
starts with needs its fork.

Try it: `simpleviz demo` serves this comparison of the bundled examples;
select the API node and press `f r`. Add `--no-open` to print the URL
without opening a browser.

## Editing

The page can edit map-form EDN files. An edit patches the file in place,
keeps comments and formatting outside the changed value, and reaches the page
through the usual live reload. Exported PNGs and SVGs, and pre-v2 vector-form files, are
read-only.

**Inspector.** Click a value or its ✎ to edit it. Scalars edit as text and
collections as EDN. Enter commits, Shift+Enter inserts a line break, Escape
cancels. `×` deletes an attribute and the bottom row adds one. The `id` row
renames a node or box, and its edges and box memberships follow. Setting
`name` also renames, to the id derived from it: lowercased, every run of
characters that can't appear in a keyword (anything but letters, digits and
`*+!_'?<>=./-`) turned into one `-`, with no `-` at either end. So
"Web Server (v2)" becomes `web-server-v2`. If that id is taken, the whole
edit is rejected. A name with no usable characters leaves the id alone.
Edges have no id; change their endpoints from the toolbar.

**Toolbar.** The toolbar at the bottom shows the tools for the current
selection, or "new node" and "new box" when nothing is selected. Creation prompts derive
the id from the name as above and accept `name::type`, so
`Web Server::frontend` creates `web-server` with type "frontend". Pick modes
wait for a click on the canvas. Every tool has a chord, shown on its button.
Chords work while no text field has focus; typing the first key opens a
pop-out above the toolbar listing what comes next, and its entries can be
clicked too. Esc cancels a pending chord or pick. The toolbar shows one
button per first key — `new`, `add`, `rename / remove`, `follow` — whose
options pop out the same way.

| Chord | Selection | Action |
| --- | --- | --- |
| `d d` | any | delete; a node or box also loses its edges and its box membership |
| `e 1` `e 2` `e 3` `e 4` | edge | direction → ← ↔ — |
| `c s` / `c t` | edge | change source / target (click the new endpoint) |
| `a e` | node, box | add edge (click the other endpoint, then name it or leave it empty) |
| `a b` | node / box | add to a box / add a box as member (click it); it leaves the box it was in |
| `a n` | box | add a node as member (click it); it leaves the box it was in |
| `r n` | box | remove node (click a member; it moves to the enclosing box or out) |
| `r b` | node | remove from box (it moves to the enclosing box or out) |
| `n n n` | none / node / box | new node / new node connected to the selection / new node inside the box |
| `n n b` | none / box | new empty box / new empty box inside the box |
| `n n s` | node, box | new node next to the selection (same box), unconnected |
| `n n c` | node, box | new node connected from the selection, in the same box |
| `n n i` | node, box | new node connected to the selection (new → selected), in the same box |
| `n n e` | edge | split the edge: A→B becomes A→X→B; A→X keeps the edge's attributes, X→B gets its direction |
| `n b` | node, box | wrap the selection in a new box, in the selection's place |
| `r r` | node, box | rename the id |
| `f r` | node, edge, box | follow the `:ref` |
| `f p` | node, box | follow the pair; with several, `f p 1`…`f p 9` (numbered in the inspector) |
| `f m` | node, box | open the `:md-ref` doc in the text panel |
| `s s` | any | select mode: click nodes and boxes to add them to the selection or drop them |
| `s a` | any | select mode with every node and box selected |
| `s r` | any | select mode: drag a rectangle; the nodes and boxes wholly inside join the selection |
| `y y` / `x x` / `d d` | select mode | copy / cut / delete the selection — a box with what it holds, and the edges among them all |
| `p p` | none / node / box | paste what was copied or cut, here or in another graph — into the selected box, else at the top level |
| `?` | any | toggle the help panel |

**Copy and paste.** `y y` and `x x` put the selection on the clipboard as
the EDN of a graph of its own — nodes, edges and boxes as the file writes
them — so `p p` pastes it into any graph, in any simpleviz tab, and a text
editor takes it too. Pasting a node or box whose id the graph already uses
(or an edge or box names) gives it the first free `-1`, `-2`, …, on its id
and its `:name`; the edges and box members among the pasted elements follow
the new ids. A pasted element loses its `:pos`, so manual layout puts it
under the rest. The browser may ask once before `p p` reads the clipboard.
Esc ends select mode.

**Layout and undo.** A relayout after an edit starts from the previous
positions, so existing elements stay put and a new node appears next to the
one it connects to. ▦ (top right) runs a fresh layout; it's disabled while the
layout is already fresh. Ctrl+Z or ↶ undoes the last edit, from an undo stack
per file that all viewers share (100 entries).

**Security.** The server binds to loopback only and accepts writes only from
its own `localhost`/`127.0.0.1` origin. It answers only requests addressed
to `localhost`, `127.0.0.1` or `[::1]`, so a web page that points its own
name at 127.0.0.1 (DNS rebinding) can't read the served files.

## Manual layout

Graphs lay themselves out left to right (ELK's layered layout). To place
things yourself, press **✥** (top right):

- The first time, every top-level node and box gets a `:pos [col row]` —
  the grid cell nearest where the layout had it — in one edit (Ctrl+Z
  takes it back). From then on the file is laid out by hand.
- While ✥ is on, the canvas shows the grid's dots and dragging a top-level
  node or box moves it, cell by cell; letting go writes its new `:pos`.
  Drag a box by any part of it — what is inside moves along. ✥ again, or
  Esc, and dragging pans again.
- The inside of each box is still laid out automatically, and so are
  nested nodes and boxes: only top-level elements take a `:pos`.
- Edges between top-level elements run straight, border to border, with
  their label in the middle; they can cross an element placed in their
  way. Edges inside a box are routed as before.
- A new top-level element without `:pos` waits in a row under the rest
  until you drag it somewhere.
- **auto** (shown while arranging) drops every `:pos`, after asking: the
  graph is laid out automatically again.
- Cells count from 0, up to 10000. A `:pos` on a nested element, or one
  that isn't two whole numbers, warns and is ignored. Moving an element
  is an edit like any other, so a comparison marks it modified. Arranging
  needs an editable file and isn't offered while comparing.

## Editing docs

"Open md" (`f m`) opens the `.md` file an element's `:md-ref` names in a
plain-text panel on the right, wider than the inspector; ⤢ makes it fill
the window and Esc docks it again. The panel stays open while you select
other elements or follow refs.

- Ctrl+S (Cmd+S) or Save writes the file, and so do closing the panel and
  opening another doc. ● marks unsaved changes; closing the browser tab
  with unsaved changes asks first.
- A file that doesn't exist yet opens empty, marked "new file", and is
  created, with its folders, on the first save.
- The panel follows the file on disk: without unsaved changes it shows
  what someone else wrote; with unsaved changes it says "changed on disk"
  — Reload takes the file, Overwrite keeps your text. A save is refused
  while an agent holds the file's lock.
- Same path rules as refs: relative to the graph file, never above the
  served folder. Only `.md` files in UTF-8, up to 1 MiB.

### vi mode

The **vi** button above a doc's text (the `:md-ref` panel, or an md page
in Edit) turns on vi keys. It stays on for every doc and every port
until you turn it off. A status line under the text shows the mode, the
keys typed so far and the `:` or `/` command being typed.

- Modes: Esc for normal, `i a I A o O` for insert, `v` and `V` to select
  characters or lines. Insert mode types like the plain text field.
- Moves, with a count: `h j k l`, `w b e W B E`, `0 ^ $`, `gg G` (`5G`
  goes to line 5), `f t F T` with `;` `,`, `{ }` between paragraphs,
  `/` `?` with `n N`, which search the plain text and wrap.
- Operators `d c y` take a move or a text object — `iw aw iW aW`,
  quotes `i" a"` (likewise `'` and backticks), brackets `i( a( ib i[ a[ i{ a{ iB i< a<` —
  and `dd cc yy` act on lines. `x X s S D C Y r J ~ p P` as in vim;
  one unnamed register, not the clipboard.
- `u` and Ctrl+R undo and redo, `.` repeats the last change.
- `:w` saves, `:q` closes the panel (an md page shows its text read
  instead) — saving, as closing does — `:wq` or `:x` does both, and
  `:q!` drops the unsaved edits, then closes. `:12` goes to line 12.
  Ctrl+S saves in any mode.
- A bare Esc in normal mode still docks a full-window panel; Ctrl, Cmd
  and Alt keys stay the browser's. Paste in insert mode — outside it a
  paste does nothing. Not there: macros, marks, named
  registers, `:s`, Ctrl+V block selection.

## md pages

`simpleviz notes.md` serves a markdown file: the page shows it
rendered, in a centered column about 90 characters wide. Edit (top
right of the column) switches to the plain text, Read back. Link graphs
and other docs with plain markdown links, which GitHub and every editor
understand too:

    The flow is in [the payment graph](pay/flow.edn).
    See [auth](auth.md) and the [token states][tokens].

    [tokens]: diagrams/tokens.edn

- The rendering covers what GitHub shows: headings (`#`, or a line of
  `=` under the text), paragraphs and line breaks (two spaces or a `\`
  at the end of a line), **bold**, *italic*, ~~strikethrough~~,
  blockquotes, horizontal rules, lists and task lists (`- [x]`), links,
  images, tables, footnotes (`[^1]` and `[^1]: …`), fenced and indented
  code blocks and `code spans`; anything else — raw HTML, emoji
  shortcodes, `==highlight==` — shows as written. Web links open in a
  new tab.
- An image shows as the picture: a `.png`, `.svg`, `.jpg`, `.jpeg`,
  `.gif` or `.webp` of the served folder, or a web URL. An exported
  graph is also its link: a click opens it beside the text.
- A line of just `---` is a horizontal rule, never the underline of a
  heading — in a presentation it starts the next slide.
- In the plain text, each line with a followable link gets a » in the
  gutter beside it; a line with several shows »2, »3 … and lists them.
- Following a graph, or an exported PNG or SVG — a click on its link or
  its » — moves the column to the left and opens it to the right of the
  text; the link and its » light up. It works like any graph:
  inspect, edit, export. "⤢ full view" opens it on its own (its crumb
  leads back to the md page, the graph beside it again), × closes it.
  The URL keeps it, so a reload shows it again. A ref followed from it,
  or its `:md-ref`, opens in place of the md page. A graph with its own
  `:theme` colors the whole page while it's open.
- Following an md file opens it in place of this one, and the trail at
  the top leads back.
- Followable: inline links, images, reference links and their
  definitions, to `.edn`, `.png`, `.svg` and `.md` files. Relative to
  the md file, never above the served folder; `#section` and `?query`
  are ignored. Links in code blocks and code spans, web links and other
  file types get no », and links to other file types don't follow.
- Unsaved text is saved before the page changes — with a link, a crumb,
  or the browser's back and forward; if the save fails, the page stays.
- A `:ref` on a graph element may name an `.md` file: following it
  opens the md page. `:md-ref` still opens the doc beside the graph,
  and its links work the same way.
- md files can't be compared, forked or exported; `simpleviz notes.md
  next` is refused.

### Presenting

Present (top right of the column) shows the md page as slides, one at a
time. A line of just `---` starts the next slide; a slide whose first
line is `>>` and a link shows the linked file to the right of its text:

    ---
    # Payments
    ## how a charge flows
    the platform team

    ---
    >> [the flow](pay/flow.edn)
    # The flow

    - the API validates, the worker charges
    ---
    >> [notes](./notes.md)
    # Open questions

- The first slide is the title slide, centered — unless it has a `>>`.
  Empty slides are dropped, so a deck may start and end with `---`;
  `---` inside a fenced code block is code.
- `>>` takes a graph (`.edn`, or an exported `.png` or `.svg`), an md
  file (shown rendered) or a picture (`.png`, `.svg`, `.jpg`, `.jpeg`,
  `.gif`, `.webp` without simpleviz EDN in it) — `>> [text](path)`, or
  `>> ![alt](pic.png)` for a picture. Being a link, it gets its » in Edit
  and `simpleviz check` reports it when broken. A plain `>> path` works
  too. The path is relative to the md file, never above the served
  folder.
- → ↓ space and Page Down step forward, ← ↑ and Page Up back, Home and
  End jump to the first and last slide. Esc closes the inspector, else
  leaves; so does "Leave presentation", the only control shown.
- A graph beside a slide works as usual — hover, click to inspect
  (read-only), pan, zoom, collapse and expand boxes — but nothing in it
  can be edited, and refs and pairs don't follow. Links on a slide are
  text, except web links.
- The URL keeps the slide (`?slide=3`), so a reload shows it again. The
  text follows the file on disk while you present.
- `simpleviz init talk.md -t presentation` writes a deck to start from,
  and `talk.edn` beside it for its graph slide. `simpleviz demo` brings
  `deck.md`, a deck about decks.

## Following refs

A `:ref` on a node, box or edge names another graph file by a path relative
to the file it's in. With the element selected, "follow ref" (`f r`) opens
that graph in place. The trail at the top leads back, and so does the
browser's back button.

- Refs may use `..` but can't leave the folder of the file the server
  started with. They must point at an `.edn` file, an exported `.png` or
  `.svg`, or an `.md` file (which opens as an md page).
- Following a ref to a missing `.edn` file creates it as an empty graph,
  folders included. In a comparison, only the side picked by the old|new
  toggle is created.
- A ref names the original, never a fork: `x-next.edn` is refused while
  comparing with `next`.
- A graph served from a PNG or SVG can't follow refs.

In `examples/demo.edn` the API node refs `api/internals.edn`, whose
"Demo overview" node refs back.

## Pairs

A `:pair` on a node or box names the same thing in another graph file:
`"views/deploy.edn#api-svc"`, a path relative to the file the pair is in,
`#`, and a node or box id there. Several views take a vector of them.
The path follows the ref rules: `..` is fine, the served folder is the
limit, and a fork is refused.

- The ⇄ mark shows elements with pairs; a red ⇄ marks one with a broken
  pair. The inspector lists them: `→` for
  pairs declared here, `←` for pairs in other files that point here, so
  declaring one side is enough. Click one, or use "follow pair" (`f p`;
  with several, `f p 1`…`f p 9`, as the inspector numbers them),
  to open that graph with the element selected; the trail leads back.
- The server finds pairs pointing here by reading every `.edn` under the
  served root's folder, except dot-folders, `node_modules` and forks. A
  pair added in another file shows once this file reloads.
- A broken pair (missing file, missing id, a fork, a path out of the
  folder) is dimmed in the inspector and warns in the banner, in
  `simpleviz check` and in `/api/errors`. `check` treats the checked
  file's folder as the served folder and only checks pairs the file
  declares.
- Following a pair never creates a file. When the id is gone, the file
  still opens, with a banner naming what's missing.
- If both a node and a box have the id, the pair means the node.

## Exporting

⇩ (top right) opens the export menu. PNG downloads the whole graph as an
image, SVG as a vector drawing, which scales without blurring and keeps its
text as text. Both embed the source EDN (the SVG in its `<metadata>`); an
export made in compare mode embeds both files.

    simpleviz extract diagram.png            # print the embedded EDN (compare export: the new file)
    simpleviz extract diagram.png --old      # compare export: the old file
    simpleviz extract diagram.svg graph.edn  # write it to a file (never overwrites)

An exported PNG or SVG works anywhere an EDN file does, read-only:

    simpleviz diagram.png                    # serve the embedded graph
    simpleviz diagram.svg next               # compare against diagram-next.svg
    simpleviz compare-export.png             # reopen the full comparison

SVG fonts are referenced, not embedded. Browsers show the SVG exactly like the
page; other tools such as Inkscape may substitute a font, so label widths can
differ slightly from their boxes.

### From the terminal

`simpleviz export` writes the same file ⇩ downloads, without opening a window:
the page renders it in a headless Chrome or Chromium.

    simpleviz export graph.edn graph.png               # PNG of graph.edn
    simpleviz export graph.edn next diff.svg           # compare export, both files embedded
    simpleviz export diagram.png diagram.svg           # re-export an export
    simpleviz export graph.edn graph.png --theme nord  # pick the theme

- The format follows the output's extension, `.png` or `.svg`. An existing
  output is kept unless you pass `--force`.
- Every box is expanded, even in a big graph that the page opens collapsed.
- The theme is the file's `:theme`. A file without one gets `--theme`, else
  light. The page's theme menu doesn't apply here.
- simpleviz looks for `google-chrome`, `chromium`, `microsoft-edge` or
  `brave-browser` on your PATH, then for the macOS apps. Set
  `SIMPLEVIZ_BROWSER` to a path or command to pick one.
- A snap browser keeps its temporary profile in `~/snap/<name>/common/`,
  since a snap can't use the system's temp folder. simpleviz deletes it
  when it's done.

## Checking a file

The page shows a file's problems in banners. To get the same report
without a browser, for example from a script or an agent:

    simpleviz check graph.edn                           # works without a server
    simpleviz check notes.md                            # an md file's broken links
    curl -s http://localhost:7373/api/errors            # the served file
    curl -s 'http://localhost:7373/api/errors?file=sub/api.edn'

`check` prints `error: ..` or one `warning: ..` line per warning and exits
1, or prints `ok`. The route answers `{"error":null,"warnings":[]}` for a
clean file. In compare mode `file` names the original, not the fork; the
report covers both sides, and each warning starts with its file's name.

For an md file, each warning names the line and the link: `line 3: pay.edn not found`, `line 7: ../x.edn leaves the served folder`.

## Write locks

Agents and scripts that write a served file directly can coordinate through
an advisory lock per file:

    curl -X POST -H 'Content-Type: application/json' \
      -d '{"owner":"agent-a","path":"sub/api.edn"}' http://localhost:7373/api/lock
    curl -X POST -H 'Content-Type: application/json' \
      -d '{"owner":"agent-a","path":"sub/api.edn"}' http://localhost:7373/api/unlock

`path` is the file's name relative to the served folder (a fork is
`graph-next.edn`) and defaults to the root file. `/api/lock` answers
`{"ok":true,"ttl":60}`, or 409 `{"error":"locked by <owner>"}` while someone
else holds the lock. Locking again as the same owner renews the 60 seconds;
after that a forgotten lock expires. The page's edits to a locked file are
refused with the same message. Nothing stops a process that writes without
asking.

## Updating the agent plugins

`simpleviz update +ai` updates simpleviz, then the plugin of each agent
CLI on your PATH, `claude`, `codex` and `grok`: it adds the marketplace
and installs the plugin where they're missing, and updates them where
they're there. A tool that fails doesn't stop the others; the command
then exits 1. Restart Claude Code to load the new plugin. The launcher of
v0.35.0 and older ignores `+ai`: run `simpleviz update` once, then
`simpleviz update +ai`.

Claude Code updates marketplace plugins in the background. To update by hand,
say right after a release:

    /plugin marketplace update simpleviz
    claude plugin update simpleviz@simpleviz

Codex: refresh the marketplace, then add the plugin again (a clean
reinstall):

    codex plugin marketplace upgrade
    codex plugin add simpleviz@simpleviz

Grok: refresh the marketplace, then update the plugin:

    grok plugin marketplace update
    grok plugin update simpleviz
