# Guide

The details the README leaves out. In the page, `?` opens a short version.

## Data format

The README example shows every attribute simpleviz reads.

- Identifiers may be keywords or strings.
- `:name` defaults to the key. `:type` colors a node's name or a box (boxes
  have their own palette), and a type keeps its color across restarts and
  edits. Any other attribute shows only in the inspector.
- `:state` — `:new`, `:in-progress`, `:blocked` or `:done` — puts a mark on
  the node's top-right corner: grey disc, blue half disc, red square, green
  disc with a check. Any other value is an ordinary attribute.
- `:ref` links the element to another graph file (see
  [Following refs](#following-refs)). A node with a ref gets a double border.
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
new file's theme. Files without `:theme` are light or dark, following the
operating system's setting.

The theme menu at the top of the page sets `:theme` in the file to a
built-in name, or removes it ("default"). It's an edit like any other: it
rewrites the file, and ↶ undoes it. In a comparison it edits the new file,
whose theme is shown. The menu is disabled for a custom `:theme` map (a
name would drop its changes; edit those in the file) and for files the
page can't edit, such as a served PNG.

Built-in themes: `light` and `dark` (the two the OS setting picks from),
`print` (white, greys, no box fills), `high-contrast`, `blueprint`, `paper`,
`solarized-light`, `solarized-dark`, `nord`, `dracula`, `carbonfox` and
`one-dark`.

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
through the usual live reload. Exported PNGs and pre-v2 vector-form files are
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
selection, or "new node" when nothing is selected. Creation prompts derive
the id from the name as above and accept `name::type`, so
`Web Server::frontend` creates `web-server` with type "frontend". Pick modes
wait for a click on the canvas. Every tool has a two-key chord, shown on its
button. Chords work while no text field has focus, and Esc cancels a pending
chord or pick.

| Chord | Selection | Action |
| --- | --- | --- |
| `d d` | any | delete; a node or box also loses its edges and its box membership |
| `e 1` `e 2` `e 3` `e 4` | edge | direction → ← ↔ — |
| `c s` / `c t` | edge | change source / target (click the new endpoint) |
| `a e` | node, box | add edge (click the other endpoint, then name it or leave it empty) |
| `a b` | node / box | add to a box / add a box as member (click it) |
| `a n` | box | add a node as member (click it) |
| `r n` | box | remove node (click a member; it moves to the enclosing box or out) |
| `r b` | node | remove from box (it moves to the enclosing box or out) |
| `n n` | none / node / box | new node / new node connected to the selection / new node inside the box |
| `c n` | box | new node inside the box, as `n n` |
| `n b` | node, box | new box around the selection, in the selection's place |
| `r r` | node, box | rename the id |
| `f r` | node, edge, box | follow the `:ref` |
| `?` | any | toggle the help panel |

**Layout and undo.** A relayout after an edit starts from the previous
positions, so existing elements stay put and a new node appears next to the
one it connects to. ▦ (top right) runs a fresh layout; it's disabled while the
layout is already fresh. Ctrl+Z or ↶ undoes the last edit, from an undo stack
per file that all viewers share (100 entries).

**Security.** The server binds to loopback only and accepts writes only from
its own `localhost`/`127.0.0.1` origin.

## Following refs

A `:ref` on a node, box or edge names another graph file by a path relative
to the file it's in. With the element selected, "follow ref" (`f r`) opens
that graph in place. The trail at the top leads back, and so does the
browser's back button.

- Refs may use `..` but can't leave the folder of the file the server
  started with. They must point at an `.edn` file or an exported `.png`.
- Following a ref to a missing `.edn` file creates it as an empty graph,
  folders included. In a comparison, only the side picked by the old|new
  toggle is created.
- A ref names the original, never a fork: `x-next.edn` is refused while
  comparing with `next`.
- A graph served from a PNG can't follow refs.

In `examples/demo.edn` the API node refs `api/internals.edn`, whose
"Demo overview" node refs back.

## Exporting

⇩ (top right) opens the export menu. PNG downloads the whole graph as an
image with the source EDN embedded. An export made in compare mode embeds both
files.

    simpleviz extract diagram.png            # print the embedded EDN (compare export: the new file)
    simpleviz extract diagram.png --old      # compare export: the old file
    simpleviz extract diagram.png graph.edn  # write it to a file (never overwrites)

An exported PNG works anywhere an EDN file does, read-only:

    simpleviz diagram.png                    # serve the embedded graph
    simpleviz diagram.png next               # compare against diagram-next.png
    simpleviz compare-export.png             # reopen the full comparison

SVG, the menu's other item, downloads the whole graph as a vector drawing,
which scales without blurring and keeps its text as text. It embeds the source
EDN too, in its `<metadata>`, both files in compare mode. Two limits:

- simpleviz can't read an SVG back yet: `extract`, serving and comparing take
  PNGs only. The embedded EDN is there for when it can.
- Fonts are referenced, not embedded. Browsers show the SVG exactly like the
  page; other tools such as Inkscape may substitute a font, so label widths can
  differ slightly from their boxes.

## Checking a file

The page shows a file's problems in banners. To get the same report
without a browser, for example from a script or an agent:

    simpleviz check graph.edn                           # works without a server
    curl -s http://localhost:7373/api/errors            # the served file
    curl -s 'http://localhost:7373/api/errors?file=sub/api.edn'

`check` prints `error: ..` or one `warning: ..` line per warning and exits
1, or prints `ok`. The route answers `{"error":null,"warnings":[]}` for a
clean file. In compare mode `file` names the original, not the fork; the
report covers both sides, and each warning starts with its file's name.

Anything after the root map other than whitespace, commas, comments and `#_`
discards is an error, because an extra `}` that closes the map early would
otherwise hide the rest of the file. The message names the line the map
closes on, where that `}` sits: `content after the end of the graph: its map
closes at line 1 — check there for an extra }`. Edits to such a file are
refused until it's fixed.

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

Claude Code updates marketplace plugins in the background. To update by hand,
say right after a release:

    /plugin marketplace update simpleviz
    claude plugin update simpleviz@simpleviz

Codex: refresh the marketplace, then add the plugin again (a clean
reinstall):

    codex plugin marketplace upgrade
    codex plugin add simpleviz@simpleviz
