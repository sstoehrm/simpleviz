# Development

## Requirements

- [babashka](https://babashka.org/) (serving + tests)
- node + npm (frontend development — compiling the Squint sources)
- A browser

## Working on the code

The frontend is written in [Squint](https://github.com/squint-cljs/squint)
ClojureScript rendered with [reagami](https://github.com/borkdude/reagami),
compiled to plain ES modules (no bundler).

    bb dev [graph.edn [suffix]]      # compile, watch sources, serve (default: examples/demo.edn)
    bb build                         # one-shot compile to public/js/ (git-ignored)
    bb test                          # compile + Clojure server tests + JS unit tests
    bb serve graph.edn [suffix]      # serve only (needs a prior bb build); suffix = compare against graph-<suffix>.edn
    bb fork graph.edn suffix         # fork graph.edn and its ref closure
    bb promote graph.edn suffix      # move the forks back over their originals
    bb check graph.edn               # print the parse error / validation warnings, exit 1 on any
    bb bundle [version]              # build a release tarball into dist/

Passing a suffix serves the file in compare mode against its fork (old → new, see [the guide](guide.md#comparing-two-versions); `server/fork.clj` creates and promotes forks): `server/diff.clj` merges the two
normalized graphs into one union graph whose elements carry a `:diff`
status (`added`/`removed`/`modified`, absent = unchanged) and, when
modified, a `:changed {attr {:old .. :new ..}}` map. The frontend only
styles those annotations — glyph-prefixed edge labels (`transform.cljs`),
status rings/ghosting and the roll-up dot (`canvas.cljs`), roll-up of
hidden changes into collapsed boxes (`prune.cljs`), legend and inspector
changes section (`app.cljs`). Single-file responses carry no diff keys.

Rendering: HTML5 canvas (HiDPI) fed by a pure scene list; layout by vendored
[ELK.js](https://github.com/kieler/elkjs) (layered, left-to-right, compound
boxes, ELK-placed edge labels). Type colors come from an FNV-1a hash into a
fixed 255-color table (golden-angle hues, linear probing on collision), so a
type keeps its color across restarts and unrelated edits (`colors.cljs`).

Sources in `src/simpleviz/`, tests in `test/simpleviz/` (run by `node --test`
against the compiled output).

Large example graphs can be generated with
`bb dev/gen-example.clj 10000 big.edn`.

The API serves one root file, alone or paired with its fork. The routes
`/api/graph`, `/api/errors`, `/api/version` and `/api/source` take `?file=<path>`
and `/api/edit` a `"path"` in its body, a path relative to the root file's
folder; `serve/resolve-path` refuses anything above that folder, non
`.edn`/`.png` targets and missing files. The page keeps the shown file and
the trail of followed refs in its query string (`?file=..&trail=..`, see
`editor/parse-nav`). In suffix mode `serve/sides` pairs the requested path
with its fork per request, so every route works in compare mode; an
embedded-compare PNG refuses the parameter.

## CI and releases

CI (`bb test`) runs on every push to main and every pull request
(`.github/workflows/ci.yml`).

Pushing a `v*` tag runs `bb bundle` and publishes the tarball as a GitHub
release (`.github/workflows/release.yml`). The bundle contains the precompiled
frontend, the server, the examples, and a serve-only `bb.edn` — end users need
only babashka. Dependabot keeps the GitHub Actions pins and npm
devDependencies current (weekly).
