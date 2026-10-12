# simpleviz, a walk through the examples

You are reading an md page: a plain markdown file that simpleviz renders. Click a link
to a graph and it opens on the right, next to this text, and works like any graph;
"⤢ full view" opens it on its own, and the trail at the top leads back here. Edit, top
right, shows the plain text; there a » beside a line follows its link.

To open this page yourself:

    simpleviz demo --no-open    # prints "demo files in <folder>"
    simpleviz <folder>/intro.md

## A graph is an EDN file

Open [the demo graph](demo.edn). Everything on the canvas comes from a map with three
keys:

    {:nodes {:api {:name "API" :type "service"}
             :db  {:type "database"}}
     :edges {[:api :db] {:direction :-> :name "queries"}}
     :boxes {:backend {:components #{:api :db}}}}

- `:nodes` are the things, `:edges` connect two of them (keyed by their endpoints),
  `:boxes` group nodes and other boxes.
- `:type` is free-form and picks a color; `"database"` draws a cylinder.
- Any other attribute, like the demo's `:replicas 3`, shows only in the inspector when
  you click the node.

The layout is automatic. Edit the file in any editor and the page follows on save.

## Edit in the page

Click an element to inspect it, click a value to change it. The toolbar at the bottom
offers what fits the selection; every tool has a key chord:

- `n n n` adds a node, connected to the selected one.
- `a e` adds an edge: press it, then click the other end.
- `d d` deletes the selection.
- Ctrl+Z undoes, `?` lists everything else.

Edits patch the file in place and keep its comments and formatting.

## Mark progress

[The states graph](states.edn) uses `:state` on nodes, each a mark on the node's corner:

| `:state`       | mark           |
| -------------- | -------------- |
| `:new`         | grey disc      |
| `:in-progress` | blue half disc |
| `:blocked`     | red square     |
| `:done`        | green check    |

Handy when a diagram doubles as a plan.

## Link graphs together

In the demo graph the API node has `:ref "api/internals.edn"`, drawn as a double border.
Select it and press `f r` to open [the API internals](api/internals.edn); its "Demo
overview" node refs back. A `:ref` may also name an md file like this one, and `:md-ref`
opens a doc beside the graph instead of in its place.

A link to another md file opens it in place of this one: try
[the notes on the API](api/notes.md).

## Compare two versions

[The fork of the demo](demo-next.edn) is the same graph after a change. `simpleviz demo`
serves both as one diagram: added elements get a green ring, modified ones amber,
removed ones stay as red ghosts. Make your own with

    simpleviz fork my-arch.edn next      # my-arch-next.edn, plus every file it refs
    simpleviz my-arch.edn next           # compare
    simpleviz promote my-arch.edn next   # keep the new version

## Big graphs

[Five thousand nodes](big-5k.edn) open with every top-level box collapsed; the panel on
the left lists them, click one to expand it.

## Take it with you

- ⇩ (top right) exports a PNG or SVG with the source embedded; simpleviz serves the
  image like the EDN file it came from. Try [an export of the demo](overview.png): it
  opens like the graph itself.
- `simpleviz check my-arch.edn` prints what the page would warn about, without a
  browser. It checks this file's links too.
- `simpleviz init my-arch.edn` writes a starter graph.
- An md page is also a talk: [the deck](deck.md) opens as a page like this one; Present,
  top right, shows it as slides, a graph beside each.

The [README](https://github.com/sstoehrm/simpleviz#readme) has the full data format, and
the [guide](https://github.com/sstoehrm/simpleviz/blob/main/docs/guide.md) every detail.
