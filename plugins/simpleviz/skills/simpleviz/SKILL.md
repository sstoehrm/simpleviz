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
- `:ref "sub/other.edn"` on a node, box or edge links another graph file, relative to the file it is in and never above the folder of the served root file; the viewer's "follow ref" (`f r`) opens it and shows a clickable trail back. In a suffix comparison it opens the referenced file's own comparison.
- `:state` on a node — `:new`, `:in-progress`, `:blocked` or `:done` — draws a mark on the node's corner (grey disc, blue half disc, red square, green check); use it to show progress on a plan or spec figure. Any other value is an ordinary attribute. A node with a `:ref` is drawn with a double border.
- `:edges` is a map keyed by `[from to]` vectors — not `:from`/`:to` maps. Direction lives in `:direction`; there is no `:bidirectional` (use `:<->`). The same pair cannot appear twice; writing both `[:a :b]` and `[:b :a]` triggers a "same connection" warning.
- Grouping is `:boxes` with `:components` — there is no `:zones`, `:groups`, or `:children`. Boxes nest by listing another box's id in `:components`. Edge endpoints may be node ids or box ids (never display names); an edge between a box and its own content — or a box and itself — is skipped with a warning.
- Identifiers may be keywords or strings, interchangeably (`:api` ≡ `"api"`); namespaced keywords keep their namespace (`:backend.server/db` ≡ `"backend.server/db"`). When a name refers to both a node and a box, an edge endpoint resolves to the node (with a warning).
- A node's attribute map may be empty or nil: `{:nodes {:api {} :db nil}}` is valid. A box without `:components` is valid too — it renders as a small node-style shell.
- An element may belong to at most one box; contested membership goes to the first box by sorted name, with a warning.
- Colors are stable: a `:type` string keeps its color across restarts and unrelated edits.
- Pre-v2 vector forms are still accepted: `:edges [{:nodes [:a :b] :direction :->}]` and `:boxes [{:name "backend" :components #{..}}]`.
- The editor rewrites the served file. Vector-form files refuse edits; convert to map form first.

## Validation is lenient — check what you wrote

Unknown node references, duplicate memberships, containment cycles, wrong shapes: the offending element is skipped and a warning banner explains it; everything else still renders. A parse error shows an error banner and keeps the last good render. Nothing fails loudly, so after every write, get the banners' text yourself — no server or browser needed:

    simpleviz check graph.edn        # from a bundle/repo dir: bb check graph.edn
    # `ok` (exit 0), or `error: ..` / one `warning: ..` line per problem (exit 1)

While a server is serving the file, its `/api/errors` route gives the same report (`$URL` is the address the server printed; `file` is relative to the served root file's folder):

    curl -s $URL/api/errors                      # {"error":null,"warnings":[]} means clean
    curl -s "$URL/api/errors?file=sub/api.edn"   # a file reached through a :ref

In compare mode `file` names the original, never the fork (`sub/api.edn`, not `sub/api-next.edn` — unlike locks); the report covers both sides, each warning prefixed with the name of the file it is in. Every warning means the picture differs from what the file says: fix it and check again until the report is clean. Check each file you wrote — a `:ref` target is checked on its own.

## Running

From a bundle/install/repo directory (repo needs `bb build` once):

    bb serve graph.edn               # default port 7373
    bb serve graph.edn --port 9000   # or -p
    bb fork graph.edn next           # graph-next.edn + forks of every referenced file
    bb serve graph.edn next          # compare mode: graph.edn → graph-next.edn, ONE merged diff view
    bb promote graph.edn next        # each fork replaces its original
    bb check graph.edn               # parse error / validation warnings, exit 1 on any
    bb serve diagram.png             # exported PNGs work in place of EDN files (embedded
                                     # source; a compare export re-opens as the comparison)

With the `simpleviz` command, installed by `install.sh` (files in `~/.simpleviz`, launcher in `~/.local/bin`) or by bbin (`bbin install https://github.com/sstoehrm/simpleviz/releases/latest/download/simpleviz.jar`; there `update` prints the bbin command and `clean-all` is unavailable):

    simpleviz graph.edn              # random free port 7370-7469, prints the URL, opens browser
    simpleviz graph.edn --no-open    # the same without a browser — use this as an agent
    simpleviz demo                   # copy the bundled examples to a temp folder and serve them
    simpleviz fork graph.edn next    # fork the graph and its ref closure
    simpleviz graph.edn next         # compare graph.edn → graph-next.edn (refs follow into
                                     # the referenced file's own comparison)
    simpleviz promote graph.edn next # move each fork over its original
    simpleviz init graph.edn         # write a starter graph file (refuses to overwrite)
    simpleviz check graph.edn        # print what the warning/error banners would say
    simpleviz update                 # install the latest release if newer
    simpleviz --version              # print the installed version
    simpleviz extract diagram.png    # print the EDN embedded in an exported PNG
                                     # (compare exports embed BOTH files: default
                                     #  prints the new one, --old the old one;
                                     #  add an out.edn arg to write a file)

To serve as an agent, run `simpleviz graph.edn --no-open` in the background: it prints `simpleviz: http://localhost:<port>` (that is `$URL`) and keeps running until you stop it.

There is no `bb diff` or similar — comparing is serving a file with the suffix of its fork (`<name>-<suffix>.<ext>`; `fork` creates it, `promote` folds it back). Two-file compare (`simpleviz old.edn new.edn`) no longer exists. In compare mode: added elements get a green `+` ring, modified an amber `~` ring (click for attribute-level old → new), removed stay visible as red dashed ghosts; nodes and boxes match by key (renaming a display `:name` is a modification, not remove+add), edges by endpoints. A legend at the top center names both files (basenames) and shows a count per status — each legend row is a button: clicking jumps to that status's next element (selecting it and centering the view, `2/3`-style position, wrap-around). Collapsed boxes hiding changes count as stops.

## Viewer

Click any node/edge/box for its full attributes. Hovering shows a tooltip with the element's name and its attributes (an unnamed edge is headed by its `[from to]` key). Drag pans, wheel zooms. Boxes collapse/expand via the `−` button in their header (a collapsed box showing an amber dot hides changes in compare mode). Theme toggle top-right. Saving the file live-reloads the page (~1s); the tab title names the served file (or `old → new` in compare mode). The ⇩ button exports the whole diagram as a PNG with the source EDN embedded as metadata (recoverable via simpleviz extract, or serve the PNG directly).

## Editing (in the browser)

Map-form files are editable in place. The inspector (right panel) is the data view: click an attribute value or its ✎ to edit inline (scalars as text, collections as raw EDN), `×` deletes an attr, a key/value row at the bottom adds one; in compare mode a modified element's old → new changes show as a card at the top. Editing tools sit in a floating toolbar at the bottom center: with nothing selected, "new node" (name prompt — the id is derived from the name: lowercased, illegal characters to dashes; `name::type` also sets the type; jumps to the new node); with a selection, that element's tools — Delete (cascades — removes touching edges and box membership); edges: direction row and source/target retarget (pick mode: click the new node/box, Esc cancels); nodes: "add edge" (pick the endpoint, then name the edge — empty leaves it unnamed), "add to box", "new node" (new connected node via name prompt), "new box" (name prompt); boxes: "add edge", "add node"/"add box" (pick a member), "new node" (inside the box, name prompt), "new box". Any element with a string `:ref` also offers "follow ref" (`f r`) — in an editable (EDN-served) graph; a PNG target can be reached but not followed from. Editing a node's or box's `name` in the inspector renames its id the same way. In compare mode the top-center legend carries the old|new toggle picking which file edits apply to. Ctrl+Z or the ⟲ button undoes the last edit; the server keeps one undo stack per file, shared by all viewers, capped at 100. Edits rewrite the file on disk, preserving comments and formatting; PNG-served sessions are read-only.

## Concurrent writes (lock before editing a served file)

When a simpleviz server is serving the file and someone else may write it too (another agent, a user editing in the browser), take the server's advisory lock around your write — `$URL` is the address the server printed:

    curl -s -X POST -H 'Content-Type: application/json' -d '{"owner":"<your-id>","path":"sub/api.edn"}' $URL/api/lock
    # ... write the file ...
    curl -s -X POST -H 'Content-Type: application/json' -d '{"owner":"<your-id>","path":"sub/api.edn"}' $URL/api/unlock

- `owner` is any string unique to you; `path` is the real file name relative to the served folder (a fork is `graph-next.edn`), default the root file. Locks are per file.
- `{"ok":true,"ttl":60}` means you hold it; HTTP 409 `{"error":"locked by <owner>"}` means wait a moment and retry — do not write.
- A lock expires after 60 seconds; repeat the lock call to renew during longer work, and always unlock when done. Re-read the file after getting the lock: it may have changed while you waited.
- While locked, browser edits to that file are refused ("locked by <owner>"). The lock is advisory — it only protects against writers that ask. No server running means no lock; just write.
