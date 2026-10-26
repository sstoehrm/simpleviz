---
name: simpleviz
description: Writes and checks simpleviz files — EDN graphs of nodes, edges and nested boxes, and markdown docs around them: slide decks, quiz and poll questions on slides, tables that calculate (formulas, also over CSV, JSON or EDN data), and bar, line and scatter charts of a table or data file — and serves, compares, exports and presents them with the simpleviz CLI. Use when creating or editing simpleviz graph EDN files or md docs, decks, quizzes, calculated tables or charts; visualizing an architecture or system diagram with simpleviz; making a talk, quiz or poll for a team, or a doc table or chart that sums, summarises or plots data, where simpleviz is in use; showing a proposed change as a before/after; or serving, forking and comparing graphs (bb serve, the simpleviz launcher, nodes/edges/boxes .edn files, exported PNGs/SVGs with embedded EDN).
---

# simpleviz

Diagrams, docs and slides from plain markdown and EDN files. A graph is an
EDN file of nodes, edges and nested boxes; an md file explains graphs and
presents as slides. `simpleviz` serves either as a page in the browser that
reloads while the files change.

What you can make — offer these when a user asks what simpleviz can do:
- **Diagrams**: a graph, laid out automatically; linked graphs (`:ref`,
  `:pair`), progress marks (`:state`), a proposed change compared with
  its fork. See Data format, Proposing a change.
- **Docs**: an md file whose links open graphs beside the text. See Md
  files.
- **Presentations**: the same md file as slides, a graph, doc or picture
  beside a slide. See Slides.
- **Quizzes and polls**: a slide that asks a question, the answers saved
  for you to read. See Quizzes.
- **Tables that calculate**: formulas in md tables, formatted and
  coloured cells, totals over a CSV, JSON or EDN file. See Tables.
- **Charts**: a table shown as a bar, line or scatter chart, or a chart
  of a CSV, JSON or EDN file alone. See Charts.

Terms used below: a *graph* is an EDN file, an *md file* a markdown doc,
*the page* what the browser shows (its *inspector* lists a clicked
element's attributes), the *served folder* the folder of the
file simpleviz was started with.

## Contents
- Writing a graph or md file: the checklist
- Data format: example, shapes that are easy to get wrong, attributes
- Md files
- Slides
- Quizzes
- Tables
- Charts
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
- [ ] 1. A server serves the file (the user's, another agent's or yours; simpleviz servers lists them)? Take its lock and re-read the file (see Concurrent writes). None does? Skip this and step 5
- [ ] 2. Write the file (a graph in map form, see Data format)
- [ ] 3. Check every file you wrote, :ref targets and forks included: simpleviz check <file>, or $URL/api/errors while served (see Checking)
- [ ] 4. Fix each warning, check again; repeat until clean
- [ ] 5. Release the lock (after the check, so the fixes are covered too)
- [ ] 6. To show it: a server already serves it? Give the user that URL; the page reloads by itself. Else start one: simpleviz <file> --no-open in the background
```

`simpleviz servers` lists the user's running servers, a line each: its
`$URL` and the absolute path of the file it was started with. A server
answers only below the secret token in its URL, so take `$URL` from there,
from the server's own output, or from the user — probing ports finds
nothing. `curl -s $URL/api/root` names that file and the folder it serves
(`{"path":"arch.edn","dir":"/abs/served/folder"}`, symlinks resolved). It
serves your file when `realpath <your-file>` lies under `dir`; that file's
lock `path` is its realpath relative to `dir`.

## Data format (canonical map forms)

```edn
{:nodes {:api {:name "API"           ; display name (defaults to the key)
               :type "service"       ; free-form string; determines color, shown as (type)
               :text "Order intake"  ; shown inside the node
               :state :in-progress   ; corner mark: :new :in-progress :blocked :done
               :lang "clojure"}      ; any other attr: shown in the inspector only
         :web {:type "frontend"}
         :db {:type "database"}      ; drawn as a database cylinder
         :cache {:type "cache"}}     ; any other type: the plain shape, its own color
 :edges {[:web :api]                 ; key = the two endpoints; order defines left/right
         {:direction :->             ; :-> | :<- | :<-> | :- (default :-)
          :name "REST"               ; edge label
          :type "http"               ; free-form; appended to the label: REST (http)
          :text "POST /orders"}}     ; shown under the label
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
  restarts and edits. On an edge it only follows the name in the label
  (`REST (http)`), so don't repeat the name there. `"database"` (any case)
  draws the node as a cylinder; use it for data stores (a cache too, when
  it should look like one). Other types are yours to name; reuse one
  string per kind across a set of graphs, e.g. `"external"` for systems
  outside the one drawn (and outside its boxes), `"queue"` for a broker or
  topic that deserves its own node — or name the edge instead
  (`:name "order events (Kafka)"`).
- `:text` on a node or box shows inside it, on an edge under its label (or
  as the label, without a name or type); wrapped at about 220 px; `\n`
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
  built-in (`:abyss :light :dark :print :high-contrast :blueprint :paper
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

- `:highlight true` on a node, box or edge draws a thin ring in the theme's
  accent color, to point at it (false or absent: none; other values
  warn). A step can switch it on or off, `[[:nodes :queue :highlight] :+
  true]`, and the animation fades it.
- `:steps [{:name "add a cache" :edits [[[:nodes :cache] :+ {:type "cache"}]
  [[:edges [:api :cache]] :+ {:direction :->}]]} ...]` at the top level
  lists states of the graph: each step's `:edits` is an
  [editscript](https://github.com/juji-io/editscript) edit vector, `[path
  op value]` with op `:+` add, `:-` remove (no value), `:r` replace,
  applied to the state before it; the file as written is the start. The
  page animates between the states, and a deck slide showing the graph
  gets a Next button. Paths walk the graph like `get-in`: an edge by its
  key `[:edges [:api :cache]]`, a box member by its id `[:boxes :b
  :components :cache]` (sets). A step that does not apply warns and
  changes nothing. The page's "compare with" diffs two steps like a
  fork comparison, and `curl -s "$URL/api/graph?steps=0,3"` answers that
  union graph (`:diff` per element). Use it to show a sequence (a migration, a request's
  path); a single proposed change is still a fork and compare.

Paths in `:ref`, `:md-ref`, `:pair` and md links are relative to the file
they are in and never reach above the served folder.

## Md files

An md file is served like a graph (`simpleviz notes.md --no-open`) and
opens rendered as GitHub markdown (headings, lists, task lists, tables,
footnotes `[^1]`, code, images; no raw HTML). Use it as the entry point
that explains a set of graphs. A fenced block whose opening fence names
its language is highlighted: `clojure`/`edn`, `bash`/`sh`, `python`,
`yaml`, `toml`, `dockerfile`, `javascript`, `typescript`, `java`, `go`,
`rust`, `c`/`cpp`, `csharp`, `kotlin`, `json`, `sql`, `diff`,
`html`/`xml`/`svg`, `css`; any other shows plain.

- Ordinary links follow in the page: `[text](sub/api.edn)`,
  `[text](other.md)`, `![x](diagram.png)`, reference links. A graph opens
  to the right of the text; an md file opens in its place. Images: `.png`
  `.svg` `.jpg` `.jpeg` `.gif` `.webp` of the served folder, or a web URL;
  an exported graph also opens beside the text on a click.
- `?compare=<suffix>` on a link to a graph (`[the change](sub/api.edn?compare=next)`,
  also after `>>`) shows it compared with its fork `sub/api-next.edn`, as
  `simpleviz sub/api.edn next` would. Refs followed inside stay compared;
  the file's other links stay plain. Use it to show a proposed change in a
  doc or talk.
- `simpleviz check notes.md` lists broken links and images: `line N:
  <dest> not found` or `leaves the served folder`, and for `?compare=` a
  missing fork or invalid suffix. It lists malformed questions (see
  Quizzes) and broken charts (see Charts) too.

## Slides

Any md file is also a deck: Present (top right of the page) shows it
one slide at a time. Write talks this way; `simpleviz init talk.md -t
presentation` writes a starter deck and its graph.

```markdown
# Checkout
## how an order flows
the platform team

---
>> [the flow](checkout.edn)
# The flow

- the API validates, the worker charges
---
>> [the change](checkout.edn?compare=queue)
# With a queue
```

- A line of just `---` starts the next slide (not inside a code block);
  the text before the first `---` is the first slide. The first slide
  is a centered title slide unless it has a `>>`. Empty slides are
  dropped.
- A slide whose first line is `>> [text](path)` shows that graph (`.edn`,
  or an exported `.png`/`.svg`), md file or picture (`>> ![alt](pic.png)`)
  to the right of its text — the slide's other lines.
- Keys: → ↓ space Page Down forward, ← ↑ Page Up back, Home and End the
  first and last slide, Esc leaves. The URL keeps the slide (`?slide=3`),
  and `$URL/?slide=1` opens the deck presenting: give the user that link
  to start a talk. The text follows the file while presenting, so you can
  append slides.
- A graph beside a slide can be hovered, inspected, panned, zoomed and
  collapsed, but not edited; refs and pairs don't follow.
- `simpleviz export talk.md talk.pdf` writes the deck as a PDF, a 16:9
  page per slide with its `>>` beside it (graphs as presented, first
  step only; questions unanswered). Text that overflows a slide is cut
  off — keep slides short.

## Quizzes

A slide whose first line is `?? name` (a name such as `cache-policy`)
asks a question; `simpleviz demo` brings `quiz.md` with every kind.

```markdown
---
>> [the architecture](arch.edn)
??? storage
# Where should session state live?

- [ ] In each node's memory
  Gone on restart, and the other nodes never see it.
- [x] In Redis, shared by all nodes
  Right: every node reads the same session.
---
?? mood
# How clear was the walkthrough?

- [ ] Clear
- [ ] Some gaps
- [ ] Lost me
```

- The task list is the 2 to 4 choices: `- [x]` a right one, `- [ ]` the
  others; no `[x]` at all makes it a poll. Lines indented two or more
  spaces under a choice explain it. The slide's other text shows above
  the choices.
- `?? name` takes a click any time, and a new click changes the answer.
  `??? name` waits for Submit before the deck goes on, then locks the
  answer, marks the right choice and shows the explanation of the
  picked one only — put what everyone should read in the slide text.
- `>>` and `??` may share a slide's first lines, in either order.
- Answers land in `talk.answers.edn` beside `talk.md` as
  `{"name" {:answer "choice text" :num 0-based-index}}`: one answer per
  question, the last one given — the presenter's click (say, the room's
  vote), not one per person. Read it to react, e.g. append the next
  slide. Presenting again starts from that file; delete it to ask again.
- Without a name or with the wrong number of choices the slide says so
  and shows the list as text; `simpleviz check` reports it: `line N: ??
  storage takes 2 to 4 choices, - [ ] lines; it has 1`.

## Tables

A GitHub table in an md file calculates: a body cell starting with `=(`
is a formula, plain Clojure; the page shows its result, the formula on
hover. A ```` ```simpleviz-table ```` block right above the table
formats its cells, and the page hides it.

````markdown
```simpleviz-table
[:b] (fn [v _] (if (> v 500) {:color "red" :text (str v " €")} (str v " €")))
```

| service | cost |
|---------|-----:|
| compute | 620  |
| storage | 140  |
| total   | =(sum :b) |
````

- Columns are letters (`:a` … `:z`, `:aa`), rows the body rows from 1.
  A range is a column, then no row (all of it), one row, or a first and
  a last row, flat: `(sum :a 4 8 :b 1 4)`. `sum`, `avg`, `count`, `min`,
  `max` and `table` (the values, a vector) take ranges and count only the
  numbers. Formula cells are in no range, so a totals row can sum its
  column. Everything else is plain Clojure: `(format "%.2f" (avg :b))`.
- A cell's value is its number, else its text. The block's pairs are a
  range and a fn, run top to bottom on each covered cell's value (a
  formula's result too) and its place `{:row :col :raw}`; a fn may take
  only the value. A string or number returned is what the cell shows and
  what the next fn gets; a map colours it (`:color`, `:background`,
  `:border`; `:text` replaces the value). Formulas see the plain values.
- Over a data file: the block starts with `>> [sales](data/sales.csv)`
  (`.csv`, `.jsonl`, `.json` or `.edn`; relative to the md file), then
  for json, jsonl and edn `>> [:item :qty [:price :net]]`, its fields (a
  csv's header names them; fields pick by name, `>> ["item" "qty"]`).
  Formulas then address the data: columns the fields in order, rows the
  records; `(rows)` is every record as a map `{:a "tea" :b 2}`. No record
  shows by itself — each cell you want is a formula:

  ````markdown
  ```simpleviz-table
  >> [invoices](invoices.csv)
  ```

  | what     | value |
  |----------|------:|
  | invoices | =(count (rows)) |
  | total    | =(sum :c) |
  ````

- Formulas run in a sandbox (SCI: no files, network or interop), a
  second each. A failing formula shows its error in its cell.
  `simpleviz check` does not run formulas: serve the file and look, or
  read the cells in the page.

## Charts

A ```` ```simpleviz-chart-table ```` block right below a table shows the
table as a chart; the page hides the table behind a Table button. A
```` ```simpleviz-chart ```` block charts a data file without a table.

````markdown
| quarter | tea | coffee |
|---------|----:|-------:|
| Q1      | 120 | 80     |
| Q2      | 140 | 95     |
| total   | =(sum :b) | =(sum :c) |

```simpleviz-chart-table
{:kind :bar :x :a :y [:b :c] :rows [1 2] :title "Sales"}
```

```simpleviz-chart
>> [the sales](sales.csv)
>> ["units" "price"]
{:kind :scatter :x :a :y [:b]}
```
````

- The map: `:kind` `:bar`, `:line` or `:scatter`; `:x` one column, `:y`
  one (`:b`) or a vector (`[:b :c]`, a series each); `:rows [from to]`
  the body rows to chart (leave out a totals row); `:stack true` and
  `:horizontal true` for bars; `:title`. Any other key is an error.
- Of a table: `:a` is its first column (usually the labels), rows its
  body rows from 1. A formula is charted by its number, not its
  formatted text; a table over a data file is charted by the cells it
  shows, not the file's records. One chart block per table, after it
  (blank lines allowed, nothing else); for a second chart, repeat the
  table.
- Alone: the first lines are a table block's data lines, `>>` the data,
  then `>>` its fields, required here: their order makes the letters,
  and they may pick a subset. Each record is a row; the chart doesn't
  group or sum — sum in a table and chart that. A line or scatter whose
  x values are all numbers gets a numeric axis, else evenly spaced
  categories.
- `simpleviz check` warns about a chart block not right below a table,
  a map that doesn't read or names a column or row that isn't there,
  and data that can't be read. It runs no formulas.

## Checking

    simpleviz check graph.edn        # or notes.md
    # `ok` (exit 0), or `error: ..` / one `warning: ..` line per problem (exit 1)

No server or browser needed. While a server runs, `/api/errors` gives the
same report (`$URL` is the address it printed, one `simpleviz servers`
lists, or the one the user gave you; `file` is relative to the served
folder):

    curl -s $URL/api/errors                      # {"error":null,"warnings":[]} means clean
    curl -s "$URL/api/errors?file=sub/api.edn"   # a file reached through a :ref or md link
    curl -s "$URL/api/errors?file=sub/api.edn&compare=next"  # both sides of a comparison,
                                                 # e.g. one an md file links with ?compare=

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
    simpleviz servers                # the user's running servers: URL and served file, a line each
    simpleviz init graph.edn         # starter file (refuses to overwrite)
    simpleviz init graph.edn -t web  # ...from ~/.simpleviz/templates/web.edn (or
                                     # $SIMPLEVIZ_HOME/templates); add your own there,
                                     # updates keep them
    simpleviz check graph.edn        # see Checking
    simpleviz fork graph.edn next    # see Proposing a change (also promote)
    simpleviz export graph.edn out.png  # PNG/SVG via headless Chrome or Chromium, never a
                                     # window (suffix for a compare export, --theme <name>,
                                     # --force to overwrite)
    simpleviz export talk.md talk.pdf   # a deck's slides as a PDF, a page each (--theme, --force)
    simpleviz extract diagram.png    # print the embedded EDN (a compare export embeds both:
                                     # --old for the old one; out.edn arg writes a file)
    simpleviz update                 # install the latest release if newer
    simpleviz --version

As an agent, always pass `--no-open` and run the server in the background:
it prints `simpleviz: http://localhost:<port>/<token>` (that is `$URL`;
the token is new on each start and keeps other users of the machine out,
so the URL is as private as the files) and runs until its process is
stopped. Leave it running while the user needs the
link; stop a server you started once they don't, and never stop one you
didn't start. To hand someone an image, run `simpleviz export` rather than
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

1. `simpleviz fork graph.edn next` writes `graph-next.edn` beside it and a
   fork of every file it refs, each a copy to edit; the originals stay
   untouched.
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

Whenever a server serves the file, take its advisory lock around your
write: the user can edit in the page at any time, and so can another
agent.

    curl -s -X POST -H 'Content-Type: application/json' -d '{"owner":"<your-id>","path":"sub/api.edn"}' $URL/api/lock
    # ... write the file ...
    curl -s -X POST -H 'Content-Type: application/json' -d '{"owner":"<your-id>","path":"sub/api.edn"}' $URL/api/unlock

- `owner` is any string unique to you; `path` is the real file name
  relative to the served folder (a fork is `graph-next.edn`; md files too).
  Leaving it out means the root file, the same as naming it. Locks are per
  file.
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
