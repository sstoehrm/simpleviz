# simpleviz

Minimal EDN-driven graph visualization. Describe nodes, directed edges, and
nested grouping boxes in an EDN file; view it as an auto-layouted SVG diagram
that live-reloads while you edit the file.

## Requirements

- [babashka](https://babashka.org/)
- A browser

## Usage

    bb serve examples/demo.edn

Open http://localhost:8080. Edit the file — the page updates automatically.
Click nodes, edges, or boxes for their full attributes. Drag to pan, wheel to zoom.

## Data format

    {:nodes {"api" {:name "API"          ; display name (defaults to the key)
                    :type "service"      ; free-form; colors the name, shown as (type)
                    :lang "clojure"}}    ; any other attr: details panel only
     :edges [{:nodes ["web" "api"]       ; vector: order defines left/right
              :direction :->             ; :-> | :<- | :<-> | :- (default :-)
              :name "REST"
              :type "http"}]
     :boxes [{:name "backend"
              :type "zone"               ; colors the box (separate palette)
              :components #{"api" "db"}}]} ; node and/or box names; boxes nest

Colors are stable: a type keeps its color across restarts and unrelated edits
(FNV-1a hash into a fixed 255-color table, golden-angle hues, linear probing
on collision).

Invalid references, duplicate box memberships, or containment cycles never
break rendering — the element is skipped and a warning banner explains it.
A parse error shows an error banner and keeps the last good render.

## Development

    bb test        # Clojure server tests + JS unit tests
    node --test test/   # JS only

Layout: vendored [ELK.js](https://github.com/kieler/elkjs) (layered,
left-to-right, compound boxes). No build step; frontend is plain ES modules.
