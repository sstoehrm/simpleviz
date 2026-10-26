# The page

What the user sees and does in the browser. SKILL.md covers the files; read
this when the user asks how to do something in the page.

## Contents
- Viewing: inspector, tooltips, boxes, themes, export
- Editing in the page: inspector edits, toolbar, select mode, chords, undo
- Md files in the page
- Compare mode: rings, legend, old|new toggle

## Viewing

Click any node, edge or box for its full attributes in the inspector (right
panel). Hovering shows a tooltip with the element's name and attributes (an
unnamed edge is headed by its `[from to]` key). Drag pans, wheel zooms.
Boxes collapse and expand via the `−` button in their header.

The theme menu top-right picks the viewer's own theme for every graph
without `:theme` (saved in the browser; "default" is `velvet`, black with a violet
accent); it never writes the file. A file's `:theme` wins, and the menu
shows it disabled.

Saving the file reloads the page (~1 s). The tab title names the served
file (`old → new` in compare mode).

The ⇩ button exports the whole diagram: PNG embeds the source EDN as
metadata, SVG in its `<metadata>`. Either is recoverable with
`simpleviz extract`, or can be served directly.

## Editing in the page

Map-form graphs are editable in place; edits rewrite the file on disk,
preserving comments and formatting. PNG- and SVG-served sessions are
read-only.

Inspector: click an attribute value or its ✎ to edit inline (scalars as
text, collections as raw EDN); `×` deletes an attribute; the key/value row
at the bottom adds one. Editing a node's or box's `name` renames its id the
same way new ids are derived (below).

Toolbar (bottom center):
- Nothing selected: `new` → "new node" (name prompt; the id is derived from the
  name: lowercased, illegal characters to dashes; `name::type` also sets
  the type; jumps to the new node) and "new box" (an empty top-level box).
- Any selection: Delete (cascades: removes touching edges and box
  membership).
- Edges: direction row; source/target retarget (pick mode: click the new
  node or box, Esc cancels); "split edge" (`n n e`, A→X→B).
- Nodes: "add edge" (pick the endpoint, then name the edge; empty leaves it
  unnamed), "add to box", "new connected node" (`n n n`), "new sibling
  node" (`n n s`), "new connected node, same box" (`n n c`), "new incoming
  node" (`n n i`), "wrap in box" (`n b`).
- Boxes: the node tools plus "add node"/"add box" (pick a member), "new
  node inside" (`n n n`), "new box inside" (`n n b`).
- Any element with a string `:ref`: "follow ref" (`f r`), in an EDN-served
  graph, with a clickable trail back; a PNG or SVG target can be reached
  but not followed from.
- `:md-ref`: "open md" (`f m`) edits the doc as plain text.
- `:pair`: the inspector lists pairs in both directions (one declared on
  either side shows on both), numbered; "follow pair" (`f p`, or
  `f p 1`…`f p 9` with several) opens the other graph with that element
  selected.

Select mode: `s s` (click nodes and boxes to add or drop them), `s a`
(all), `s r` (drag a rectangle). It takes `y y` copy, `x x` cut, `d d`
delete; a box comes with what it holds, plus the edges among them. The copy
goes to the system clipboard as the EDN of a graph of its own, and `p p`
pastes it into any graph (into the selected box, else top level); a taken
id gets `-1`, `-2`, … on its id and `:name`, and `:pos` is dropped. Esc
ends select mode.

Chords are shown on the buttons; typing a first key pops out what comes
next, clickable. A toggle at the toolbar's right end switches between every
tool in a row and one button per first key with pop-outs (saved per
browser).

✥ places top-level nodes and boxes by hand and writes `:pos`.

Ctrl+Z or ↶ undoes the last edit. The server keeps one undo stack per file,
shared by all viewers, capped at 100.

## Md files in the page

An md file opens rendered; Edit shows the plain text (with optional vi
keys), where a » beside a line follows its link. A followed graph opens to
the right of the text (`?side=<path>` in the URL). Present shows the
slides.

## Compare mode

Added elements get a green `+` ring, modified ones an amber `~` ring (click
for attribute-level old → new, shown as a card at the top of the
inspector), removed ones stay visible as red dashed ghosts. A collapsed box
with an amber dot hides changes.

The legend at the top center names both files (basenames) and counts each
status. Each legend row is a button that jumps to that status's next
element (selecting and centering it, `2/3`-style position, wrapping
around); collapsed boxes hiding changes count as stops. The legend's
old|new toggle picks which file edits apply to.
