---
name: simpleviz
description: Use when creating or editing simpleviz graph EDN files, visualizing an architecture or system diagram with simpleviz, or serving and comparing graphs (bb serve, the simpleviz launcher, nodes/edges/boxes .edn files)
---

# simpleviz — graph EDN authoring and usage

EDN-driven graph visualization: nodes, directed edges, nested grouping boxes; auto-layouted canvas that live-reloads while the file is edited.

## Data format (canonical map forms)

```edn
{:nodes {:api {:name "API"           ; display name (defaults to the key)
               :type "service"       ; free-form string; determines color, shown as (type)
               :lang "clojure"}}     ; any other attr: shown in the click inspector only
 :edges {[:web :api]                 ; key = the two endpoints; order defines left/right
         {:direction :->             ; :-> | :<- | :<-> | :- (default :-)
          :name "REST"               ; edge label
          :type "http"}}             ; free-form; colors/labels like node types
 :boxes {:backend                    ; key is the box id AND its display name
         {:type "zone"               ; free-form; colors the box (separate palette)
          :components #{:api :storage}}   ; node ids and/or box ids
         :storage {:type "zone" :components #{:db :cache}}}}
```

Rules that are easy to get wrong:
- `:nodes` is a MAP keyed by id — not a vector. There is no `:id`, `:label`, or nested `:attrs` key; the display key is `:name`, and every other key in the node map is a free-form attribute shown in the inspector.
- `:edges` is a map keyed by `[from to]` vectors — not `:from`/`:to` maps. Direction lives in `:direction`; there is no `:bidirectional` (use `:<->`). The same pair cannot appear twice; writing both `[:a :b]` and `[:b :a]` triggers a "same connection" warning.
- Grouping is `:boxes` with `:components` — there is no `:zones`, `:groups`, or `:children`. Boxes nest by listing another box's id in `:components`. Edge endpoints may be node ids or box names; an edge between a box and its own content — or a box and itself — is skipped with a warning.
- Identifiers may be keywords or strings, interchangeably (`:api` ≡ `"api"`). When a name refers to both a node and a box, an edge endpoint resolves to the node (with a warning).
- A node's attribute map may be empty or nil: `{:nodes {:api {} :db nil}}` is valid.
- An element may belong to at most one box; contested membership goes to the first box by sorted name, with a warning.
- Colors are stable: a `:type` string keeps its color across restarts and unrelated edits.
- Pre-v2 vector forms are still accepted: `:edges [{:nodes [:a :b] :direction :->}]` and `:boxes [{:name "backend" :components #{..}}]`.

## Validation is lenient — it never fails fast

Unknown node references, duplicate memberships, containment cycles, wrong shapes: the offending element is skipped and a warning banner explains it; everything else still renders. A parse error shows an error banner and keeps the last good render. Do not expect exceptions or refusals to start.

## Running

From a bundle/install/repo directory (repo needs `bb build` once):

    bb serve graph.edn               # default port 7373
    bb serve graph.edn --port 9000   # or -p
    bb serve old.edn new.edn         # compare mode: ONE merged diff view (old → new)

With the launcher installed by `install.sh` (files in `~/.simpleviz`, launcher in `~/.local/bin`):

    simpleviz graph.edn              # random free port 7370-7379, prints the URL, opens browser
    simpleviz old.edn new.edn        # compare mode
    simpleviz update                 # install the latest release if newer
    simpleviz --version              # print the installed version

There is no `bb diff` or similar — comparing is just passing two files. In compare mode: added elements get a green `+` ring, modified an amber `~` ring (click for attribute-level old → new), removed stay visible as red dashed ghosts; nodes match by key, boxes by name, edges by endpoints. A legend at the bottom center names both files and shows a count per status — each legend row is a button: clicking jumps to that status's next element (selecting it and centering the view, `2/3`-style position, wrap-around). Collapsed boxes hiding changes count as stops.

## Viewer

Click any node/edge/box for its full attributes. Drag pans, wheel zooms. Boxes collapse/expand via the `−` button in their header (a collapsed box showing an amber dot hides changes in compare mode). Theme toggle top-right. Saving the file live-reloads the page (~1s).
