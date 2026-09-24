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
    bb jar [version]                 # build dist/simpleviz.jar, the release jar bbin installs
    bb jar:smoke [version]           # build the jar and run it like bbin's shim does

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

Every user command lives in `server/cli.clj` (namespace `cli`). The
install.sh launcher keeps `update` and `clean-all` and runs
`bb --config ~/.simpleviz/bb.edn -m cli` for the rest; the release jar
starts in `simpleviz.main` (`server/simpleviz/main.clj`), which calls
`cli/-main` directly, or re-runs it as `bb -cp <jar> -m cli` when a
`bb.edn` in the caller's folder would shadow the jar's files. The CLI runs
in the caller's folder, so the server reads `public/`, `examples/` and
`VERSION` from the classpath: the repo and the tarball put their root
(`"."`) on it, and the jar packs the files inside.

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

CI (`bb test`, then `bb jar:smoke`) runs on every push to main and every
pull request (`.github/workflows/ci.yml`), once per babashka version in its
matrix: only the last two minor releases (currently 1.12 and 1.13), pinned
to their latest patch. When a new minor release comes out, move the matrix
up, and raise `MIN_BB` in `install.sh` to the older of the two; the release
workflow builds with the newer one.

Pushing a `v*` tag runs `bb bundle` and `bb jar:smoke`, then publishes the
tarball and `simpleviz.jar` as a GitHub release
(`.github/workflows/release.yml`). The tarball contains the precompiled
frontend, the server, the examples, `VERSION` and a serve-only `bb.edn`;
the jar holds the same plus the server's dependencies (listed in
`THIRD-PARTY-NOTICES.md`; `bb jar` fails when one is missing). End users
need only babashka. Dependabot keeps the GitHub Actions pins and npm
devDependencies current (weekly).
