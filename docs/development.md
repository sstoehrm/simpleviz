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

A graph's `:steps` (`server/steps.clj`, on
[editscript](https://github.com/juji-io/editscript)) are read in
`serve/parse-graph`: every state is normalized on its own and the payload
carries them as `:steps [{:name :edits :graph}]`, the top level staying
the file as written. `/api/edit` and `/api/copy` take a `:step`: ops then
run on that state's EDN text and the step's `:edits` become the diff from
the state before. The page keeps one layout cache per step, seeds a
step's first layout from the one shown, and animates between scenes
(`tween.cljs`, painted through `canvas/start-tween!`). `?steps=a,b`
on `/api/graph` diffs state a with state b through `diff/union`, the
fork comparison's code, into a read-only union graph.

Rendering: HTML5 canvas (HiDPI) fed by a pure scene list; layout by vendored
[ELK.js](https://github.com/kieler/elkjs) (layered, left-to-right, compound
boxes, ELK-placed edge labels). ELK runs in a Web Worker
(`public/vendor/elk-worker.min.js`), so the page stays live while a big graph
lays out; the page loads only elkjs' API (`elk-api.js`), which posts the
graph to the worker. Type colors come from an FNV-1a hash into a
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
`.edn`/`.png`/`.svg` targets and missing files. The page keeps the shown file and
the trail of followed refs in its query string (`?file=..&trail=..`, see
`editor/parse-nav`). In suffix mode `serve/sides` pairs the requested path
with its fork per request, so every route works in compare mode; a
compare export (PNG or SVG) refuses the parameter. `/api/root` names the root file, so the page can tell an md root from a graph before it loads anything, and the canonical served folder (`dir`), so an agent can tell which files a server serves; md pages read and save through `/api/text`, and `/api/errors` reports an md file's broken links, malformed questions and broken charts (`serve/md-warnings`: links scanned by `server/mdlinks.cljc`, slides parsed by `server/deck.cljc`, both of which the page shares, and chart blocks by `calc/chart-warnings`).

## CI and releases

CI (`bb test`, then `bb jar:smoke`) runs on every push to main and every
pull request (`.github/workflows/ci.yml`), once per babashka version in its
matrix: the floor, `MIN_BB` in `install.sh`, and the latest release. The
floor is 1.13.220, the first babashka whose bundled http-kit has the fixes
for GHSA-qwg7-h8j3-975p, GHSA-h7mc-gw66-cwmx, GHSA-4gx4-prhm-c233 and
GHSA-j9v4-rr7v-r6pw (every 1.12 has them). Raise it, in the matrix too,
when a babashka release fixes a vulnerability in what it bundles; move the
latest up as releases come out. The release workflow builds with the
latest.

Cut a release with `bb release vX.Y.Z` from a clean `main` that matches
`origin/main`. It writes the version into the plugin manifests
(`plugins/simpleviz/.claude-plugin/plugin.json`,
`plugins/simpleviz/.codex-plugin/plugin.json`, and
`plugins/simpleviz/plugin.json`),
commits `release: vX.Y.Z`, creates the annotated tag and pushes `main` and
the tag together. Claude Code updates a plugin only when that version
changes, so the skill reaches users together with the release it
documents; skill changes merged in between wait for the next release.

The tag runs `.github/workflows/release.yml`: it first checks that the
manifests carry the tag's version (`bb release:check vX.Y.Z` — a tag
pushed by hand without `bb release` fails here), then runs `bb bundle` and
`bb jar:smoke` and publishes the tarball and `simpleviz.jar` as a GitHub
release. The tarball contains the precompiled
frontend, the server, the examples, `VERSION` and a serve-only `bb.edn`;
the jar holds the same plus the server's dependencies (listed in
`THIRD-PARTY-NOTICES.md`; `bb jar` fails when one is missing). End users
need only babashka. Renovate keeps the GitHub Actions pins, the npm
devDependencies and the bb.edn deps current (weekly, `renovate.json`).

The vulnerability gate (`.github/workflows/vulns.yml`) builds an SBOM of
the npm packages, the vendored elkjs and the bb.edn deps, scans it with
OSV-Scanner, and fails only on findings in CISA's KEV catalog or with EPSS >= 0.1
and CVSS >= 7.0. [vuln-gate.md](vuln-gate.md) has the policy, how to
suppress a finding in `.vuln-ignore.yaml`, and how other repos reuse it.
