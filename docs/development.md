# Development

## Requirements

- [babashka](https://babashka.org/) (serving + tests)
- node + npm (frontend development — compiling the Squint sources)
- A browser

## Working on the code

The frontend is written in [Squint](https://github.com/squint-cljs/squint)
ClojureScript rendered with [reagami](https://github.com/borkdude/reagami),
compiled to plain ES modules (no bundler).

    bb dev [graph.edn [new.edn]]     # compile, watch sources, serve (default: examples/demo.edn)
    bb build                         # one-shot compile to public/js/ (git-ignored)
    bb test                          # compile + Clojure server tests + JS unit tests
    bb serve graph.edn [new.edn]     # serve only (needs a prior bb build)
    bb bundle [version]              # build a release tarball into dist/

Passing two graph files serves them in compare mode (old → new, see the
README's "Comparing two versions"): `server/diff.clj` merges the two
normalized graphs into one union graph whose elements carry a `:diff`
status (`added`/`removed`/`modified`, absent = unchanged) and, when
modified, a `:changed {attr {:old .. :new ..}}` map. The frontend only
styles those annotations — glyph-prefixed edge labels (`transform.cljs`),
status rings/ghosting and the roll-up dot (`canvas.cljs`), roll-up of
hidden changes into collapsed boxes (`prune.cljs`), legend and inspector
changes section (`app.cljs`). Single-file responses carry no diff keys.

Rendering: HTML5 canvas (HiDPI) fed by a pure scene list; layout by vendored
[ELK.js](https://github.com/kieler/elkjs) (layered, left-to-right, compound
boxes, ELK-placed edge labels).

Sources in `src/simpleviz/`, tests in `test/simpleviz/` (run by `node --test`
against the compiled output).

Large example graphs can be generated with
`bb dev/gen-example.clj 10000 big.edn`.

## CI and releases

CI (`bb test`) runs on every push to main and every pull request
(`.github/workflows/ci.yml`).

Pushing a `v*` tag runs `bb bundle` and publishes the tarball as a GitHub
release (`.github/workflows/release.yml`). The bundle contains the precompiled
frontend, the server, the examples, and a serve-only `bb.edn` — end users need
only babashka. Dependabot keeps the GitHub Actions pins and npm
devDependencies current (weekly).
