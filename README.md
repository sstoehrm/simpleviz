# simpleviz

Minimal EDN-driven graph visualization. Describe nodes, directed edges, and
nested grouping boxes in an EDN file; view it as an auto-layouted canvas diagram
that live-reloads while you edit the file.

## Getting started

Quickest install (Linux, needs [babashka](https://babashka.org/), curl and tar):

    curl -fsSL https://raw.githubusercontent.com/sstoehrm/simpleviz/main/install.sh | bash
    simpleviz ~/.simpleviz/examples/demo.edn   # or any graph.edn; picks a free port 7370-7379
    simpleviz init my-arch.edn                 # write a starter file to edit

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
zoom. The `−` button in a box header collapses it to a single node; big
graphs (500+ nodes) open with all top-level boxes collapsed.

(Running from a git clone instead requires a build step — see
[docs/development.md](https://github.com/sstoehrm/simpleviz/blob/main/docs/development.md).)

## Data format

    {:nodes {:api {:name "API"           ; display name (defaults to the key)
                   :type "service"       ; free-form; colors the name, shown as (type)
                   :lang "clojure"}      ; any other attr: inspector panel only
             :web {:type "frontend"}
             :db  {:type "database"}}
     :edges {[:web :api]                 ; key: endpoints (nodes or boxes), order defines left/right;
                                         ; the same edge cannot appear twice
             {:direction :->             ; :-> | :<- | :<-> | :- (default :-)
              :name "REST"
              :type "http"}}
     :boxes {:backend                    ; key is the box id
             {:name "Backend"            ; display name (defaults to the key)
              :type "zone"               ; colors the box (separate palette)
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

## Exporting

The ⇩ button downloads the diagram as a PNG of the whole graph, with the
source EDN embedded as image metadata — an exported picture is never a
dead end. An export made in compare mode embeds BOTH input files; other
exports embed the one served file.

    simpleviz extract diagram.png            # print the embedded EDN
                                             # (from a compare export: the NEW file)
    simpleviz extract diagram.png graph.edn  # write it to a file instead (won't overwrite)
    simpleviz extract diagram.png --old      # from a compare export: the OLD file

Exported PNGs are also accepted anywhere an EDN file is — the server
reads the embedded source back out:

    simpleviz diagram.png                    # serve the embedded graph
    simpleviz old.png new.edn                # any mix of PNG and EDN in compare mode
    simpleviz compare-export.png             # re-opens as the full comparison

Or extract the EDN explicitly:

    simpleviz extract diagram.png before.edn --old
    simpleviz extract diagram.png after.edn
    simpleviz before.edn after.edn

## Comparing two versions

Pass two files to compare architectures: `bb serve old.edn new.edn`. Both
render as ONE merged diagram — added elements get a green `+` ring, modified
ones an amber `~` ring (click for an attribute-level old → new list), and
removed ones stay visible as red, dashed, ghosted shapes. Nodes and boxes
match by key, edges by their endpoints (flipping the pair or changing
`:direction` counts as modified). Layout follows the new file; removed
elements keep their old place. A collapsed box hiding any change shows an
amber dot. The legend at the bottom counts the changes per status — click
a row to jump through them (selects and centers each element, wraps
around). Both files live-reload.

## Claude Code plugin

This repo doubles as a [Claude Code](https://claude.com/claude-code) plugin
marketplace. The plugin teaches Claude the graph EDN format and the CLI, so
it can author and serve simpleviz diagrams for you:

    /plugin marketplace add sstoehrm/simpleviz
    /plugin install simpleviz@simpleviz

## Codex plugin

The same skill is available to Codex through this repository's plugin
marketplace:

    codex plugin marketplace add sstoehrm/simpleviz
    codex plugin add simpleviz@simpleviz
