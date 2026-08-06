# simpleviz

Minimal EDN-driven graph visualization. Describe nodes, directed edges, and
nested grouping boxes in an EDN file; view it as an auto-layouted canvas diagram
that live-reloads while you edit the file.

## Requirements

- [babashka](https://babashka.org/) (serving + tests)
- node + npm (frontend development — compiling the Squint sources)
- A browser

## Usage

    bb serve examples/demo.edn              # default port 7373
    bb serve examples/demo.edn --port 9000  # or -p 9000
    bb serve examples/big-5k.edn            # 5k-node stress example
                                            # (bb dev/gen-example.clj 10000 big.edn for more)

Open http://localhost:7373. Edit the file — the page updates automatically.
Click nodes, edges, or boxes for their full attributes. Drag to pan, wheel to zoom.

## Data format

    {:nodes {:api {:name "API"           ; display name (defaults to the key)
                   :type "service"       ; free-form; colors the name, shown as (type)
                   :lang "clojure"}}     ; any other attr: inspector panel only
     :edges {[:web :api]                 ; key: endpoints, order defines left/right;
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

Colors are stable: a type keeps its color across restarts and unrelated edits
(FNV-1a hash into a fixed 255-color table, golden-angle hues, linear probing
on collision).

Validation runs server-side (via [malli](https://github.com/metosin/malli)):
invalid references, duplicate box memberships, or containment cycles never
break rendering — the element is skipped and a warning banner explains it.
A parse error shows an error banner and keeps the last good render.

## Development

The frontend is written in [Squint](https://github.com/squint-cljs/squint)
ClojureScript rendered with [reagami](https://github.com/borkdude/reagami),
compiled to plain ES modules (no bundler).

    bb dev [graph.edn]   # compile, watch sources, serve (default: examples/demo.edn)
    bb build             # one-shot compile to public/js/ (git-ignored)
    bb test              # compile + Clojure server tests + JS unit tests
    bb serve graph.edn   # serve only (needs a prior bb build)

Rendering: HTML5 canvas (HiDPI) fed by a pure scene list; layout by vendored
[ELK.js](https://github.com/kieler/elkjs) (layered, left-to-right, compound
boxes, ELK-placed edge labels).

Sources in `src/simpleviz/`, tests in `test/simpleviz/` (run by `node --test`
against the compiled output).

## Releases

Release tarballs on the GitHub releases page contain the precompiled frontend
plus the server — only babashka is needed to run them (no node/npm):

    tar xzf simpleviz-vX.Y.Z.tar.gz
    cd simpleviz-vX.Y.Z
    bb serve examples/demo.edn

CI (`bb test`) runs on every push and pull request. Pushing a `v*` tag runs
`bb bundle`, which builds the tarball into `dist/` and publishes it as a
GitHub release; `bb bundle` also works locally.
