# Install script + launcher

**Date:** 2026-08-09
**Status:** Approved

## Purpose

One-command install of simpleviz from GitHub releases for end users: a
launcher on `PATH` that serves any EDN graph on a random local port and
opens the browser. Runtime dependency stays babashka only.

## `install.sh` (repo root, standalone / curl-pipeable)

1. Hard-checks `curl` and `tar`; hard-checks `bb` exists and is >=
   **1.3.0** (version from `bb version`; clear error with
   https://babashka.org install pointer). No node check — the release
   bundle ships the precompiled frontend.
2. Queries `https://api.github.com/repos/sstoehrm/simpleviz/releases/latest`,
   reads the tag and the `.tar.gz` asset URL (no jq dependency — grep/sed
   over the JSON).
3. Downloads and extracts to a temp dir (`mktemp -d`, cleaned via trap).
4. Replaces `~/.simpleviz` with the bundle contents (`public/`, `server/`,
   `examples/`, serve-only `bb.edn`). The directory is fully
   installer-managed — a comment in the script and a note in the install
   output say so.
5. Writes `~/.simpleviz/VERSION` (the release tag) and copies itself to
   `~/.simpleviz/install.sh` (used by `simpleviz update`).
6. Writes the launcher to `~/.local/bin/simpleviz` (`mkdir -p`, `chmod +x`).
7. Warns if `~/.local/bin` is not on `PATH`.
8. Prints installed version and a usage hint.

Idempotent: re-running reinstalls cleanly.

## Launcher `~/.local/bin/simpleviz` (bash, written by the installer)

    simpleviz <graph.edn> [new.edn]   # serve; two files = compare mode
    simpleviz update                  # fetch latest release if newer
    simpleviz --version | version     # print installed version
    simpleviz --help | -h             # usage

- Checks `bb` >= 1.3.0 (same check as installer; launcher must not
  assume install-time state still holds).
- Absolutizes the EDN paths (`realpath`) — the server must run from
  `~/.simpleviz` so it finds `public/`. Missing file → clear error.
- No args → usage, mentioning the bundled
  `~/.simpleviz/examples/demo.edn`.
- **Port:** shuffles 7370–7379, probes each with bash `/dev/tcp`
  (connect succeeds = busy), takes the first free one; all busy → error
  listing the range.
- Starts `bb serve <files> --port $PORT` from `~/.simpleviz` in the
  background, polls the port until it accepts (timeout ~10s), prints
  `simpleviz: http://localhost:$PORT`, opens it via `xdg-open` when
  available (address printed either way), then `wait`s on the server so
  Ctrl-C stops it.
- `update`: reads `~/.simpleviz/VERSION`, queries the latest release
  tag; same → "already up to date"; different → runs
  `~/.simpleviz/install.sh` (which repeats the full install).

## Out of scope

- macOS `open` fallback (Linux target; xdg-open or printed URL).
- Installing babashka itself.
- `--port` override in the launcher (use `bb serve` directly for that).

## Testing

- `bash -n` on both scripts (syntax gate; also part of self-review).
- No automated network test against GitHub releases. Manual
  verification: run `install.sh` for real, `simpleviz examples-path`,
  confirm printed URL responds, run a second instance (different port),
  `simpleviz --version`, `simpleviz update` (expect "already up to
  date").

## README

"Getting started" gains the one-liner install
(`curl -fsSL https://raw.githubusercontent.com/sstoehrm/simpleviz/main/install.sh | bash`)
and the `simpleviz` usage, alongside the existing tarball instructions.
