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
select the API node and press `f r`.

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
| `c n` | box | new node inside the box |
| `n n` | none / node | new node / new node connected to the selection |
| `n b` | node, box | new box around the selection, in the selection's place |
| `r r` | node, box | rename the id |
| `f r` | node, edge, box | follow the `:ref` |
| `?` | any | toggle the help panel |

**Layout and undo.** A relayout after an edit starts from the previous
positions, so existing elements stay put and a new node appears next to the
one it connects to. ⟳ (top right) runs a fresh layout. Ctrl+Z or ⟲ undoes
the last edit, from an undo stack per file that all viewers share (100
entries).

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

⇩ downloads a PNG of the whole graph with the source EDN embedded. An export
made in compare mode embeds both files.

    simpleviz extract diagram.png            # print the embedded EDN (compare export: the new file)
    simpleviz extract diagram.png --old      # compare export: the old file
    simpleviz extract diagram.png graph.edn  # write it to a file (never overwrites)

An exported PNG works anywhere an EDN file does, read-only:

    simpleviz diagram.png                    # serve the embedded graph
    simpleviz diagram.png next               # compare against diagram-next.png
    simpleviz compare-export.png             # reopen the full comparison

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
