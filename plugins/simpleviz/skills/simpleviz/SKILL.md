---
name: simpleviz
description: Writes and checks simpleviz files — EDN graphs of nodes, edges and nested boxes, and markdown docs and slide decks that link them — and serves, compares, exports and presents them with the simpleviz CLI. Use when creating or editing simpleviz graph EDN files or md docs and decks that link graphs, visualizing an architecture or system diagram with simpleviz, or serving, forking and comparing graphs (bb serve, the simpleviz launcher, nodes/edges/boxes .edn files, exported PNGs/SVGs with embedded EDN).
---

# simpleviz

Diagrams, docs and slides from plain markdown and EDN files. A graph is an
EDN file of nodes, edges and nested boxes; an md file explains graphs and
presents as slides. `simpleviz` serves either as a page in the browser that
reloads while the files change.

Terms used below: a *graph* is an EDN file, an *md file* a markdown doc,
*the page* what the browser shows (its *inspector* lists a clicked
element's attributes), the *served folder* the folder of the
file simpleviz was started with.

## Contents
- Writing a graph or md file: the checklist
- Data format: example, shapes that are easy to get wrong, attributes
- Md files and slides
- Checking
- Running
- Proposing a change: fork and compare
- Concurrent writes (lock before editing a served file)
- The page
- Old patterns

Reference files, read only when needed:
- [reference/page.md](reference/page.md): the page in the browser
  (inspector, toolbar, keyboard chords, compare legend, export menu). Read
  it when the user asks how to do something in the page.

## Writing a graph or md file

Validation is lenient: an invalid element is skipped with a warning banner
and the rest still renders; a parse error keeps the last good render.
Nothing fails loudly, so checking is the only way to know a file is right.
Copy this checklist and work through it:

```
- [ ] 1. Served by a running server? Take its lock (see Concurrent writes)
- [ ] 2. Write the file in map form (see Data format)
- [ ] 3. simpleviz check <file> — every file you wrote, :ref targets included
- [ ] 4. Fix each warning, check again; repeat until it prints ok
- [ ] 5. Release the lock
- [ ] 6. To show it: simpleviz <file> --no-open in the background, give the user the URL
```

## Data format (canonical map forms)

```edn
{:nodes {:api {:name "API"           ; display name (defaults to the key)
               :type "service"       ; free-form string; determines color, shown as (type)
               :text "Order intake"  ; shown inside the node
               :state :in-progress   ; corner mark: :new :in-progress :blocked :done
               :lang "clojure"}      ; any other attr: shown in the inspector only
         :web {:type "frontend"}
         :db {:type "database"}      ; drawn as a database cylinder
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

Shapes that are easy to get wrong:
- `:nodes` is a MAP keyed by id, not a vector. There is no `:id`, `:label`
  or nested `:attrs`; the display key is `:name`, every other key is a
  free-form attribute.
- `:edges` is a map keyed by `[from to]` vectors, not `:from`/`:to` maps.
  Direction lives in `:direction`; there is no `:bidirectional` (use
  `:<->`). A pair appears once: `[:a :b]` and `[:b :a]` together warn
  "same connection".
- Grouping is `:boxes` with `:components`; there is no `:zones`, `:groups`
  or `:children`. Boxes nest by listing another box's id.
- Edge endpoints are node or box ids, never display names. An edge between
  a box and its own content, or a box and itself, is skipped with a
  warning. When an id names both a node and a box, an endpoint resolves to
  the node (with a warning).
- Identifiers may be keywords or strings interchangeably (`:api` ≡
  `"api"`); namespaced keywords keep their namespace
  (`:backend.server/db` ≡ `"backend.server/db"`).
- An element belongs to at most one box; contested membership goes to the
  first box by sorted name, with a warning.
- A node's attribute map may be empty or nil (`{:api {} :db nil}`); a box
  without `:components` renders as a small node-style shell.

Attributes simpleviz reads:
- `:type` colors a node's name or a box, and keeps its color across
  restarts and edits. `"database"` (any case) draws the node as a cylinder;
  use it for data stores.
- `:text` on a node or box shows inside it, wrapped at about 220 px; `\n`
  or a vector of strings for more lines. Use it for the line or two a
  reader needs at a glance; whole specs belong in an md file (`:md-ref`).
- `:state` on a node (`:new`, `:in-progress`, `:blocked`, `:done`) draws a
  grey disc, blue half disc, red square or green check on its corner; use
  it to show progress on a plan or spec. Other values are ordinary
  attributes.
- `:ref "sub/other.edn"` on a node, box or edge links another graph (or an
  md file). The node gets a double border; "follow ref" in the page opens
  it with a trail back, in a comparison into the referenced file's own
  comparison.
- `:md-ref "docs/specs/api.md"` on a node or box names the md file that
  describes it (spec, ADR, notes); set it when such a doc exists. `.md`
  only. The element gets a dotted border and the page can edit the doc —
  so lock it before writing it while a server runs.
- `:pair "views/deploy.edn#api-svc"` (or a vector of them) on a node or box
  names the same element in another graph. The page marks both sides with
  ⇄ and jumps between them. A missing file or id is a warning.
- `:theme` at the top level sets the colors for every viewer and export: a
  built-in (`:light :dark :print :high-contrast :blueprint :paper
  :solarized-light :solarized-dark :nord :dracula :carbonfox :one-dark
  :tabak-light :tabak-dark`) or overrides on one, `{:base :nord :accent
  "#b58900" :edge "#81a1c1"}` (`#hex`, `rgb()`, `hsl()`; the keys are in
  the [guide's Themes section](https://github.com/sstoehrm/simpleviz/blob/main/docs/guide.md#themes)). It wins over the viewer's choice. An unknown
  name, key or value warns and is skipped.
- `:pos [col row]` on a top-level node or box places it on a grid of 20 px
  cells (whole numbers from 0). Once any element has one, the file is laid
  out by hand: boxes still arrange their insides, edges between top-level
  elements run straight. The page writes `:pos` when someone drags; don't
  invent coordinates unless asked to keep a given arrangement. On a nested
  element it warns and is ignored.

Paths in `:ref`, `:md-ref`, `:pair` and md links are relative to the file
they are in and never reach above the served folder.

## Md files and slides

An md file is served like a graph (`simpleviz notes.md --no-open`) and
opens rendered as GitHub markdown (headings, lists, task lists, tables,
footnotes, code, images; no raw HTML). Use it as the entry point that
explains a set of graphs.

- Ordinary links follow in the page: `[text](sub/api.edn)`,
  `[text](other.md)`, `![x](diagram.png)`, reference links. A graph opens
  to the right of the text.
- Slides: a line of just `---` starts the next slide. A slide whose first
  line is `>> [the API](sub/api.edn)` shows that graph — or an md file, or
  a picture (`>> ![alt](pic.png)`) — beside its text. Write talks this way;
  `simpleviz init talk.md -t presentation` writes a starter deck and its
  graph.
- Questions: a slide whose first line is `?? name` asks one. Its task list
  is the 2 to 4 choices (`- [x]` the right one, none for a poll); lines
  indented under a choice explain it. `??? name` waits for Submit before
  the deck goes on, then marks the right choice and shows the explanation
  of the one picked. `>>` and `??` may share a slide's first lines.
- Answers land in `talk.answers.edn` beside `talk.md` as
  `{"name" {:answer "choice text" :num 0-based-index}}`. Read it to react,
  e.g. append the next slide (the presented text follows the file).
  Presenting again starts from that file; delete it to ask again.
- `?compare=<suffix>` on a link to a graph (`[the change](sub/api.edn?compare=next)`,
  also after `>>`) shows it compared with its fork `sub/api-next.edn`, as
  `simpleviz sub/api.edn next` would. Refs followed inside stay compared;
  the file's other links stay plain. Use it to show a proposed change in a
  doc or talk.
- `simpleviz check notes.md` lists broken links: `line N: <dest> not found`
  or `leaves the served folder`, and for `?compare=` a missing fork or
  invalid suffix.

## Checking

    simpleviz check graph.edn        # or notes.md
    # `ok` (exit 0), or `error: ..` / one `warning: ..` line per problem (exit 1)

No server or browser needed. While a server runs, `/api/errors` gives the
same report (`$URL` is the address it printed; `file` is relative to the
served folder):

    curl -s $URL/api/errors                      # {"error":null,"warnings":[]} means clean
    curl -s "$URL/api/errors?file=sub/api.edn"   # a file reached through a :ref

In compare mode `file` names the original, never the fork (`sub/api.edn`,
not `sub/api-next.edn`, unlike locks); the report covers both sides, each
warning prefixed with its file's name. Every warning means the picture
differs from what the file says. Anything after the root map (the rest of
the file behind an extra `}`) is a parse error too.

## Running

    simpleviz graph.edn --no-open    # serve on a free port 7370-7469; prints the URL
    simpleviz notes.md --no-open     # serve an md file
    simpleviz diagram.png --no-open  # exported PNGs/SVGs serve like EDN (embedded source;
                                     # a compare export re-opens as the comparison)
    simpleviz demo                   # copy the bundled examples to a temp folder, serve them
    simpleviz init graph.edn         # starter file (refuses to overwrite)
    simpleviz init graph.edn -t web  # ...from ~/.simpleviz/templates/web.edn (or
                                     # $SIMPLEVIZ_HOME/templates); add your own there,
                                     # updates keep them
    simpleviz check graph.edn        # see Checking
    simpleviz export graph.edn out.png  # PNG/SVG via headless Chrome or Chromium, never a
                                     # window (suffix for a compare export, --theme <name>,
                                     # --force to overwrite)
    simpleviz extract diagram.png    # print the embedded EDN (a compare export embeds both:
                                     # --old for the old one; out.edn arg writes a file)
    simpleviz update                 # install the latest release if newer
    simpleviz --version

As an agent, always pass `--no-open` and run the server in the background:
it prints `simpleviz: http://localhost:<port>` (that is `$URL`) and runs
until stopped. To hand someone an image, run `simpleviz export` rather than
asking them to click ⇩.

`simpleviz` comes from `install.sh` (files in `~/.simpleviz`, launcher in
`~/.local/bin`) or bbin (there `update` prints the bbin command and
`clean-all` is unavailable). In a repo or bundle checkout without it, the
same commands run as `bb serve`, `bb fork`, `bb promote`, `bb check`
(a repo needs `bb build` once; `bb serve` uses port 7373, `--port N`/`-p N`
changes it).

## Proposing a change: fork and compare

There is no diff command; comparing is serving a graph with the suffix of
its fork (`<name>-<suffix>.<ext>`).

1. `simpleviz fork graph.edn next` writes `graph-next.edn` and a fork of
   every file it refs.
2. Edit the forks with the writing checklist; `simpleviz check` each fork
   file, and lock a fork by its real name (`graph-next.edn`).
3. `simpleviz graph.edn next --no-open` shows one merged view: added
   elements green `+`, modified amber `~`, removed as red dashed ghosts.
   Nodes and boxes match by key (a changed `:name` is a modification, not
   remove + add), edges by endpoints. Refs follow into each referenced
   file's own comparison.
4. Once accepted, `simpleviz promote graph.edn next` moves each fork over
   its original.

## Concurrent writes (lock before editing a served file)

When a server is serving the file and someone else may write it too
(another agent, the user editing in the page), take the server's advisory
lock around your write:

    curl -s -X POST -H 'Content-Type: application/json' -d '{"owner":"<your-id>","path":"sub/api.edn"}' $URL/api/lock
    # ... write the file ...
    curl -s -X POST -H 'Content-Type: application/json' -d '{"owner":"<your-id>","path":"sub/api.edn"}' $URL/api/unlock

- `owner` is any string unique to you; `path` is the real file name
  relative to the served folder (a fork is `graph-next.edn`; md files too),
  default the root file. Locks are per file.
- `{"ok":true,"ttl":60}` means you hold it; HTTP 409 `{"error":"locked by
  <owner>"}` means wait a moment and retry — do not write.
- A lock expires after 60 seconds; repeat the lock call to renew during
  longer work, and always unlock when done. Re-read the file after getting
  the lock: it may have changed while you waited.
- While locked, edits from the page to that file are refused; an unlocked
  write shows the user a conflict instead of being lost. The lock is
  advisory and only protects against writers that ask. No server running
  means no lock; just write.

## The page

Edits in the page rewrite the file, preserving comments and formatting;
saving a file reloads the page within about a second. The page's theme
menu never writes the file — to theme a file, write `:theme`. For the
inspector, toolbar, keyboard chords, compare legend and export menu, read
[reference/page.md](reference/page.md) when the user asks how to do
something in the page.

## Old patterns

- Pre-v2 vector forms still parse — `:edges [{:nodes [:a :b] :direction :->}]`,
  `:boxes [{:name "backend" :components #{..}}]` — but the page refuses to
  edit them. Convert to map form first.
- Two-file compare (`simpleviz old.edn new.edn`) no longer exists; use fork
  and a suffix.
- An install older than this skill may not know `--no-open` or `demo`:
  run `simpleviz update`.
