# simpleviz

Minimal EDN-driven graph visualization. Describe nodes, directed edges, and
nested grouping boxes in an EDN file; view it as an auto-layouted canvas diagram
that live-reloads while you edit the file.

## Getting started

Quickest install (Linux, needs [babashka](https://babashka.org/), curl and tar):

    curl -fsSL https://raw.githubusercontent.com/sstoehrm/simpleviz/main/install.sh | bash
    simpleviz ~/.simpleviz/examples/demo.edn   # or any graph.edn; picks a free port 7370-7379

`simpleviz --version` prints the installed release; `simpleviz update`
fetches the latest one. The install lives in `~/.simpleviz` (managed by the
installer) plus a launcher in `~/.local/bin`.

Alternatively, run from a tarball by hand:

Grab the latest tarball from the
[releases page](https://github.com/sstoehrm/simpleviz/releases) — it contains
the precompiled frontend, so all you need is
[babashka](https://babashka.org/) and a browser:

    tar xzf simpleviz-vX.Y.Z.tar.gz
    cd simpleviz-vX.Y.Z
    bb serve examples/demo.edn              # default port 7373
    bb serve examples/demo.edn --port 9000  # or -p 9000
    bb serve examples/big-5k.edn            # 5k-node stress example
    bb serve examples/demo.edn examples/demo-next.edn   # compare two versions

Open http://localhost:7373. Edit the file — the page updates automatically.
Click nodes, edges, or boxes for their full attributes. Drag to pan, wheel to
zoom.

(Running from a git clone instead requires a build step — see
[docs/development.md](https://github.com/sstoehrm/simpleviz/blob/main/docs/development.md).)

## Data format

    {:nodes {:api {:name "API"           ; display name (defaults to the key)
                   :type "service"       ; free-form; colors the name, shown as (type)
                   :lang "clojure"}}     ; any other attr: inspector panel only
     :edges {[:web :api]                 ; key: endpoints (nodes or boxes), order defines left/right;
                                         ; the same edge cannot appear twice
             {:direction :->             ; :-> | :<- | :<-> | :- (default :-)
              :name "REST"
              :type "http"}}
     :boxes {:backend                    ; key is the box id (and display name)
             {:type "zone"               ; colors the box (separate palette)
              :components #{:api :db}}}} ; node and/or box ids; boxes nest

Identifiers may be keywords or strings. The pre-v2 vector forms
(`:edges [{:nodes [..] ..}]`, `:boxes [{:name ".." ..}]`) are still accepted.
Writing both `[:a :b]` and `[:b :a]` produces a "same connection" warning.

Edge endpoints may be nodes or boxes. An edge between a box and its own
content — or a box and itself — is skipped with a warning; when a name is
both a node and a box, the edge gets the node.

Colors are stable: a type keeps its color across restarts and unrelated edits
(FNV-1a hash into a fixed 255-color table, golden-angle hues, linear probing
on collision).

Validation runs server-side (via [malli](https://github.com/metosin/malli)):
invalid references, duplicate box memberships, or containment cycles never
break rendering — the element is skipped and a warning banner explains it.
A parse error shows an error banner and keeps the last good render.

## Comparing two versions

Pass two files to compare architectures: `bb serve old.edn new.edn`. Both
render as ONE merged diagram — added elements get a green `+` ring, modified
ones an amber `~` ring (click for an attribute-level old → new list), and
removed ones stay visible as red, dashed, ghosted shapes. Nodes match by
key, boxes by name, edges by their endpoints (flipping the pair or changing
`:direction` counts as modified). Layout follows the new file; removed
elements keep their old place. A collapsed box hiding any change shows an
amber dot. Both files live-reload.

## Claude Code plugin

This repo doubles as a [Claude Code](https://claude.com/claude-code) plugin
marketplace. The plugin teaches Claude the graph EDN format and the CLI, so
it can author and serve simpleviz diagrams for you:

    /plugin marketplace add sstoehrm/simpleviz
    /plugin install simpleviz@simpleviz
