---
name: simpleviz
description: Use when creating or editing simpleviz graph EDN files, visualizing an architecture or system diagram with simpleviz, or serving and comparing graphs (bb serve, the simpleviz launcher, nodes/edges/boxes .edn files, exported PNGs with embedded EDN)
---

# simpleviz — graph EDN authoring and usage

EDN-driven graph visualization: nodes, directed edges, nested grouping boxes; auto-layouted canvas that live-reloads while the file is edited.

## Data format (canonical map forms)

```edn
{:nodes {:api {:name "API"           ; display name (defaults to the key)
               :type "service"       ; free-form string; determines color, shown as (type)
               :lang "clojure"}      ; any other attr: shown in the click inspector only
         :web {:type "frontend"}
         :db {:type "database"}
         :cache {:type "cache"}}
 :edges {[:web :api]                 ; key = the two endpoints; order defines left/right
         {:direction :->             ; :-> | :<- | :<-> | :- (default :-)
          :name "REST"               ; edge label
          :type "http"}}             ; free-form; colors/labels like node types
 :boxes {:backend                    ; key is the box id
         {:name "Backend"            ; display name (defaults to the key)
          :type "zone"               ; free-form; colors the box (separate palette)
          :components #{:api :storage}}   ; node ids and/or box ids
         :storage {:type "zone" :components #{:db :cache}}}}
```

Rules that are easy to get wrong:
- `:nodes` is a MAP keyed by id — not a vector. There is no `:id`, `:label`, or nested `:attrs` key; the display key is `:name`, and every other key in the node map is a free-form attribute shown in the inspector.
- `:edges` is a map keyed by `[from to]` vectors — not `:from`/`:to` maps. Direction lives in `:direction`; there is no `:bidirectional` (use `:<->`). The same pair cannot appear twice; writing both `[:a :b]` and `[:b :a]` triggers a "same connection" warning.
- Grouping is `:boxes` with `:components` — there is no `:zones`, `:groups`, or `:children`. Boxes nest by listing another box's id in `:components`. Edge endpoints may be node ids or box ids (never display names); an edge between a box and its own content — or a box and itself — is skipped with a warning.
- Identifiers may be keywords or strings, interchangeably (`:api` ≡ `"api"`); namespaced keywords keep their namespace (`:backend.server/db` ≡ `"backend.server/db"`). When a name refers to both a node and a box, an edge endpoint resolves to the node (with a warning).
- A node's attribute map may be empty or nil: `{:nodes {:api {} :db nil}}` is valid. A box without `:components` is valid too — it renders as a small node-style shell.
- An element may belong to at most one box; contested membership goes to the first box by sorted name, with a warning.
- Colors are stable: a `:type` string keeps its color across restarts and unrelated edits.
- Pre-v2 vector forms are still accepted: `:edges [{:nodes [:a :b] :direction :->}]` and `:boxes [{:name "backend" :components #{..}}]`.
- The editor rewrites the served file. Vector-form files refuse edits; convert to map form first.

## Validation is lenient — it never fails fast

Unknown node references, duplicate memberships, containment cycles, wrong shapes: the offending element is skipped and a warning banner explains it; everything else still renders. A parse error shows an error banner and keeps the last good render. Do not expect exceptions or refusals to start.

## Running

From a bundle/install/repo directory (repo needs `bb build` once):

    bb serve graph.edn               # default port 7373
    bb serve graph.edn --port 9000   # or -p
    bb serve old.edn new.edn         # compare mode: ONE merged diff view (old → new)
    bb serve diagram.png             # exported PNGs work in place of EDN files (embedded
                                     # source; a compare export re-opens as the comparison)

With the launcher installed by `install.sh` (files in `~/.simpleviz`, launcher in `~/.local/bin`):

    simpleviz graph.edn              # random free port 7370-7379, prints the URL, opens browser
    simpleviz old.edn new.edn        # compare mode
    simpleviz init graph.edn         # write a starter graph file (refuses to overwrite)
    simpleviz update                 # install the latest release if newer
    simpleviz --version              # print the installed version
    simpleviz extract diagram.png    # print the EDN embedded in an exported PNG
                                     # (compare exports embed BOTH files: default
                                     #  prints the new one, --old the old one;
                                     #  add an out.edn arg to write a file)

There is no `bb diff` or similar — comparing is just passing two files. In compare mode: added elements get a green `+` ring, modified an amber `~` ring (click for attribute-level old → new), removed stay visible as red dashed ghosts; nodes and boxes match by key (renaming a display `:name` is a modification, not remove+add), edges by endpoints. A legend at the top center names both files (basenames) and shows a count per status — each legend row is a button: clicking jumps to that status's next element (selecting it and centering the view, `2/3`-style position, wrap-around). Collapsed boxes hiding changes count as stops.

## Viewer

Click any node/edge/box for its full attributes. Hovering shows the id to reference in the EDN file as a tooltip — nodes/boxes their bare id, edges their `[from to]` key. Drag pans, wheel zooms. Boxes collapse/expand via the `−` button in their header (a collapsed box showing an amber dot hides changes in compare mode). Theme toggle top-right. Saving the file live-reloads the page (~1s); the tab title names the served file (or `old → new` in compare mode). The ⇩ button exports the whole diagram as a PNG with the source EDN embedded as metadata (recoverable via simpleviz extract, or serve the PNG directly).

## Editing (in the browser)

Map-form files are editable in place. The inspector (right panel) is the data view: click an attribute value or its ✎ to edit inline (scalars as text, collections as raw EDN), `×` deletes an attr, a key/value row at the bottom adds one; in compare mode a modified element's old → new changes show as a card at the top. Editing tools sit in a floating toolbar at the bottom center: with nothing selected, "add node" (id prompt, jumps to the new node); with a selection, that element's tools — Delete (cascades — removes touching edges and box membership); edges: direction row and source/target retarget (pick mode: click the new node/box, Esc cancels); nodes: "add edge" (pick the endpoint), "add to box", "add node" (new connected node via id prompt), "new box"; boxes: "add edge", "add node"/"add box" (pick a member). In compare mode the top-center legend carries the old|new toggle picking which file edits apply to. Ctrl+Z or the ⟲ button undoes the last edit; the server keeps one undo stack per file, shared by all viewers, capped at 100. Edits rewrite the file on disk, preserving comments and formatting; PNG-served sessions are read-only.
