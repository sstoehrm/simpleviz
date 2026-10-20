# Diagrams

A diagram is an EDN file of nodes, edges and boxes. simpleviz lays it
out, draws it and redraws it as the file changes:

    simpleviz init my-arch.edn     # write a starter graph
    simpleviz my-arch.edn          # serve it

Click an element to inspect and change its attributes; the toolbar at
the bottom adds, connects, groups and deletes. Every edit patches the
file in place and keeps its comments.

## Data format

    ;; optional, top level: :theme — a built-in (:nord, :dracula, …) or {:base :nord :accent "#b58900"}
    ;; optional, on a top-level node or box: :pos [col row] — placed by hand on a 20 px grid (✥ in the page writes it)
    {:nodes {:api {:name "API"           ; display name (defaults to the key)
                   :type "service"       ; free-form; colors the name, shown as (type)
                   :text "Order intake"  ; shown inside the node (boxes too); "\n" or a vector for more lines
                   :lang "clojure"       ; any other attr: inspector panel only
                   :ref "sub/api.edn"    ; another graph file, relative to this one — "follow ref" opens it;
                                         ; the node gets a double border
                   :pair "views/deploy.edn#api-svc" ; the same thing in another graph — "follow pair" jumps to it
                   :md-ref "docs/api.md" ; the markdown doc describing it (node or box) — dotted border; "open md" edits it
                   :state :in-progress}  ; :new | :in-progress | :blocked | :done — a mark on the node's corner
             :web {:type "frontend"}
             :db  {:type "database"}}     ; drawn as the database cylinder
     :edges {[:web :api]                 ; key: endpoints (nodes or boxes), order defines left/right;
                                         ; the same edge cannot appear twice
             {:direction :->             ; :-> | :<- | :<-> | :- (default :-)
              :name "REST"
              :type "http"}}
     :boxes {:backend                    ; key is the box id
             {:name "Backend"            ; display name (defaults to the key)
              :type "zone"               ; colors the box (separate palette)
              :components #{:api :db}}}
     :steps [{:name "add a cache"        ; optional: states of the graph, each an
              :edits [[[:nodes :cache] :+ {:type "cache"}]]}]} ; editscript change of the one before

Identifiers may be keywords or strings. An invalid element is skipped with a
warning banner instead of breaking the render. The guide has
[the full rules](guide.md#data-format).

## What you can do with one

- [Themes](guide.md#themes) — a built-in theme or your own colors, per file.
- [Editing](guide.md#editing) — the inspector and the toolbar, in the page.
- [Manual layout](guide.md#manual-layout) — ✥ places nodes and boxes by
  hand on a 20 px grid.
- [Steps](guide.md#steps) — `:steps` lists states of the graph; the page
  animates between them, in a deck too (Next).
- [Comparing two versions](guide.md#comparing-two-versions) — `simpleviz
  fork my-arch.edn next`, then `simpleviz my-arch.edn next` shows what
  was added, changed and removed.
- [Following refs](guide.md#following-refs) and [pairs](guide.md#pairs) —
  zoom into another graph, or jump to the same thing in another view.
- [Docs on elements](guide.md#editing-docs) — `:md-ref` opens a
  markdown doc beside the graph (see [Documents](documents.md)).
- [Exporting](guide.md#exporting) — PNG or SVG with the source embedded;
  simpleviz serves the export like the EDN file.
- [Checking a file](guide.md#checking-a-file) — `simpleviz check
  my-arch.edn` reports what the page would warn about.
