# Headless export — design

`simpleviz export` writes a graph's PNG or SVG from the command line,
without a browser window: the same file ⇩ downloads, made by the page
itself in a headless Chrome.

Figure: `.blend/specs/2026-10-02-headless-export.edn` (serve it with `simpleviz`).

## Goal

Agents (through the simpleviz skill) and people at a terminal get an
image of a graph with one command — to attach to a PR, show in a chat,
put in docs. The result is byte-for-byte the ⇩ export of the fully
expanded graph: same painter, same embedded EDN, so `extract` and
serving work on it. CI pipelines and batch runs are not a goal of this
version.

## Command line

    simpleviz export <graph.edn|.png|.svg> [<suffix>] <out.png|out.svg> [--theme <name>] [--force]

| Example | Result |
|---|---|
| `simpleviz export graph.edn graph.png` | PNG of graph.edn |
| `simpleviz export graph.edn next diff.svg` | compare export graph.edn → graph-next.edn, both files embedded |
| `simpleviz export diagram.png diagram.svg` | re-export from an exported file; a compare export stays a compare |
| `simpleviz export graph.edn g.png --theme nord` | nord, unless the file sets its own `:theme` (which wins, as in the page) |

- The **last positional** is the output; its extension picks the format
  (`.png`/`.svg`, any case). Anything else is a usage error.
- The output is **never overwritten** without `--force`, as `extract`
  and `init` refuse to.
- `--theme` must be one of `themes/NAMES`; checked before anything starts.
- The **input** gets the checks `simpleviz <file>` gives, with the same
  messages — missing file, invalid suffix, missing fork, export without
  embedded EDN — because export starts the server the same way
  (`serve/start!`), in-process, on a free port. They fail before a
  browser is looked for.
- Success prints `wrote <out>` and exits 0; any failure prints
  `simpleviz: <reason>` on stderr and exits 1.
- `bb export` runs the same (bb.edn and the release bundle's bb.edn).

## Page hook

`window.simplevizExport({format, theme})` — `format` `"png"`|`"svg"`,
`theme` a built-in name or null — returns a Promise of `{data}`: base64
for PNG, the SVG text for SVG. It rejects with the page's own message
(`Graph error: …`, `Render error: …`) when there is nothing to export.

1. **Waits for the first load** to end: a graph, or an error (reject).
2. **Theme:** a given theme becomes `:theme-pref` in page state — never
   in browser storage — and `effective-theme` is applied, so a file's
   `:theme` still wins. Without one, headless Chrome reports a light OS:
   the light theme, unless the file sets one. Reproducible.
3. **Expands every box** (`:collapsed-boxes` emptied, relayout), so a
   big graph exports whole, not as its collapsed overview. A very large
   graph pays for the full ELK layout; the driver's timeout allows it.
4. **Waits for a settled layout**: a scene, `:layouting` false, nothing
   collapsed — or an error (reject).
5. **Produces with the menu's code:** `export-png!`/`export-svg!` split
   into producers (`png-bytes`, `svg-text`, both async, embedding the
   sources) and thin download wrappers. The hook calls the producers,
   so ⇩ and the CLI share one path and give identical files.

The hook is always defined: one function on a page served on
127.0.0.1 only.

## Browser driver (`server/browser.clj`)

**Finding a browser**, first hit wins:

1. `SIMPLEVIZ_BROWSER` — a path or a command on PATH. Set but not
   found is an error naming the value.
2. On PATH: `google-chrome`, `google-chrome-stable`, `chromium`,
   `chromium-browser`, `microsoft-edge`, `brave-browser`.
3. macOS apps: `/Applications/{Google Chrome,Chromium,Microsoft Edge,Brave Browser}.app/Contents/MacOS/<name>`.

None: `export needs Chrome or Chromium — install one or set SIMPLEVIZ_BROWSER`.

**Launching:** `<browser> --headless --remote-debugging-port=0
--user-data-dir=<fresh profile> --no-first-run --no-default-browser-check
about:blank`. Chrome picks a free port and prints
`DevTools listening on ws://127.0.0.1:<port>/devtools/browser/<id>` on
stderr; the driver reads that line — no port to choose, no race.

**Profile folder.** A snap browser (the command resolves to
`/usr/bin/snap`, or lives under `/snap/`) can't see the host's `/tmp` or
hidden folders in home (verified: `~/.cache/…` fails with
"Failed to create …/SingletonLock"). Its profile goes to a fresh folder
in `~/snap/<command name>/common/`; every other browser's to a fresh
folder in the system temp dir. The browser is killed and **waited for**
before the folder is deleted (deleting under a live Chrome fails), in a
`finally`, so errors and timeouts clean up too.

**CDP:** a minimal client over `babashka.http-client.websocket`: send
`{id, method, params}`, deliver each reply to the waiting request by
`id`, ignore events. Steps: GET `http://127.0.0.1:<port>/json/list`
and connect to the `about:blank` page target's `webSocketDebuggerUrl`,
`Page.navigate` to the server URL, then `Runtime.evaluate` of the hook
call with `awaitPromise` and `returnByValue`. A rejected promise
(`exceptionDetails` in the result) becomes the error message.

**Timeouts:** 15 s for the DevTools line, 120 s for the hook. A timeout
fails naming the step and the wait, then cleans up. A browser that exits
before printing the line fails with the last lines of its stderr.

## Orchestration (`export` in cli.clj)

Parse and check arguments → start the server in-process on a free port
(as `serve!` does, minus printing and blocking) → find and launch the
browser → navigate, call the hook → write the output (bytes for PNG, the
text as UTF-8 for SVG) → `wrote <out>` → exit, which ends server and
browser (the `finally` has already stopped the browser).

## Errors

| Case | Message (after `simpleviz: `) |
|---|---|
| wrong argument count, unknown flag, output not .png/.svg | usage |
| output exists | `<out> already exists (--force overwrites)` |
| unknown theme | `unknown theme: <x> (one of …)` |
| input problems | as `simpleviz <file>` today |
| no browser | `export needs Chrome or Chromium — install one or set SIMPLEVIZ_BROWSER` |
| `SIMPLEVIZ_BROWSER` not found | `SIMPLEVIZ_BROWSER=<v> not found` |
| browser exits early / no DevTools line in 15 s | `could not start <browser>: <last stderr lines>` / `<browser> did not start within 15 s` |
| graph error in the page | the page's message, e.g. `Graph error: …` |
| hook still running after 120 s | `export did not finish within 120 s` |

## Testing

- **bb unit tests** (`test/browser_test.clj`): browser discovery with
  injected env/PATH/file-exists functions (override wins, PATH order,
  macOS paths, nothing found, override not found); snap detection and
  profile folder choice; reading the DevTools URL from stderr lines;
  CDP reply correlation over a fake socket.
- **CLI tests** (`test/cli_test.clj`, as processes): usage errors,
  existing output without `--force`, unknown theme, missing input, an
  export without EDN — all before a browser is needed.
- **End to end** (`test/export_test.clj`): when a browser is found,
  export `examples/demo.edn` to PNG and SVG, a compare export, and a
  `--theme` run; check the PNG signature, that `extract` gives back the
  source (both sides for compare), and that the theme changes the
  SVG's background. Without a browser the test prints that it was
  skipped. GitHub's Ubuntu runners ship Chrome, so CI runs it.
- **JS:** the producer split is covered by the end-to-end run (the hook
  calls the same producers ⇩ does); `bb test:js` must stay green.

## Docs

`simpleviz --help`, README, docs/guide.md (Exporting) and the plugin
skill (when an agent needs an image, it runs `simpleviz export`; export
never opens a window, so no `--no-open`). The in-page help is unchanged.
