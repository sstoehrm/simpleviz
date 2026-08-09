# Install Script + Launcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `install.sh` installs the latest GitHub release to `~/.simpleviz` and a `simpleviz` launcher to `~/.local/bin` that serves graphs on a random free port (7370–7379), prints the URL, and opens the browser.

**Architecture:** One self-contained bash script (`install.sh`, repo root, curl-pipeable) containing the launcher as a quoted heredoc emitted by a `write_launcher` function. An env source-guard makes the functions testable without running the installer. Spec: `docs/superpowers/specs/2026-08-09-install-script-design.md`.

**Tech Stack:** bash (`set -euo pipefail`), curl, tar, coreutils (`shuf`, `sort -V`, `realpath`, `mktemp`). No jq. Runtime dependency of the installed app: babashka only.

## Global Constraints

- Hard requirements checked by BOTH installer and launcher: `bb` exists and `bb --version` >= **1.3.0**. Installer additionally hard-checks `curl` and `tar`. **No node check anywhere.**
- `~/.simpleviz` is fully installer-managed: reinstalls `rm -rf` and recreate it. Overridable via `SIMPLEVIZ_HOME` (test hook); launcher bin dir via `SIMPLEVIZ_BIN` (default `~/.local/bin`).
- Port selection: shuffle 7370–7379, probe with bash `/dev/tcp` (successful connect = busy), take the first free; all busy → error naming the range.
- The launcher always prints `simpleviz: http://localhost:<port>`; `xdg-open` is attempted only if present and never fatal.
- `simpleviz update`: same tag as `~/.simpleviz/VERSION` → "already up to date"; different → re-run `~/.simpleviz/install.sh`.
- Launcher passes one or two EDN files through to `bb serve` (compare mode); paths absolutized with `realpath` because the server runs from `~/.simpleviz`.
- Quote all path expansions (spaces-safe). Heredoc delimiter for the launcher is quoted (`'LAUNCHER'`) so nothing expands at install time.
- Testing per spec: `bash -n` syntax gates + manual e2e against the real latest release using the env overrides — no automated network tests, no new bb tasks.
- Commit style: `feat:` / `docs:` as in git history.

---

### Task 1: `install.sh` with embedded launcher

**Files:**
- Create: `install.sh` (repo root)

**Interfaces:**
- Consumes: GitHub REST `releases/latest` for `sstoehrm/simpleviz`; the release `.tar.gz` layout (`simpleviz-<tag>/{public,server,examples,bb.edn,README.md}`).
- Produces: `install.sh` exposing functions `check_deps`, `fetch_release` (sets `TAG`, `TARBALL_URL`), `install_files`, `write_launcher`, `main`; running it normally executes `main`, while `SIMPLEVIZ_INSTALL_SOURCED=1 source install.sh` only defines the functions (the test hook Task 2's e2e also relies on). The generated launcher supports `<graph.edn> [new.edn]`, `update`, `--version`/`version`, `--help`/`-h`/no-args.

- [ ] **Step 1: Write `install.sh`**

```bash
#!/usr/bin/env bash
# simpleviz installer — installs the latest GitHub release into
# ~/.simpleviz (a directory this installer fully manages: reinstalls
# replace it) and a launcher into ~/.local/bin/simpleviz.
#
#   curl -fsSL https://raw.githubusercontent.com/sstoehrm/simpleviz/main/install.sh | bash
#
# Env overrides: SIMPLEVIZ_HOME (default ~/.simpleviz),
#                SIMPLEVIZ_BIN  (default ~/.local/bin)
set -euo pipefail

REPO="sstoehrm/simpleviz"
API_URL="https://api.github.com/repos/$REPO/releases/latest"
SIMPLEVIZ_HOME="${SIMPLEVIZ_HOME:-$HOME/.simpleviz}"
BIN_DIR="${SIMPLEVIZ_BIN:-$HOME/.local/bin}"
MIN_BB="1.3.0"

die() { echo "install: $*" >&2; exit 1; }

check_deps() {
  local c have
  for c in curl tar; do
    command -v "$c" >/dev/null 2>&1 || die "$c is required"
  done
  command -v bb >/dev/null 2>&1 \
    || die "babashka (bb) not found — install it from https://babashka.org"
  have=$(bb --version | sed -E 's/[^0-9.]//g')
  [ "$(printf '%s\n' "$MIN_BB" "$have" | sort -V | head -n1)" = "$MIN_BB" ] \
    || die "babashka $have is too old (need >= $MIN_BB)"
}

fetch_release() { # sets TAG and TARBALL_URL
  local json
  json=$(curl -fsSL "$API_URL") || die "could not query $API_URL"
  TAG=$(grep -m1 '"tag_name"' <<<"$json" | sed -E 's/.*: *"([^"]+)".*/\1/')
  TARBALL_URL=$(grep -m1 -o '"browser_download_url" *: *"[^"]*\.tar\.gz"' <<<"$json" \
                | sed -E 's/.*"(https[^"]+)".*/\1/')
  [ -n "$TAG" ] && [ -n "$TARBALL_URL" ] \
    || die "no .tar.gz asset found in the latest release"
}

install_files() {
  local dir
  # deliberately not `local`: the EXIT trap runs after this function's
  # scope is gone, so the variable must survive it
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  echo "downloading $TARBALL_URL"
  curl -fsSL "$TARBALL_URL" -o "$tmp/bundle.tar.gz"
  tar xzf "$tmp/bundle.tar.gz" -C "$tmp"
  dir=$(find "$tmp" -mindepth 1 -maxdepth 1 -type d | head -n1)
  [ -n "$dir" ] || die "unexpected tarball layout (no top-level directory)"
  rm -rf "$SIMPLEVIZ_HOME"
  mkdir -p "$SIMPLEVIZ_HOME"
  cp -R "$dir"/. "$SIMPLEVIZ_HOME"/
  echo "$TAG" >"$SIMPLEVIZ_HOME/VERSION"
  # keep a copy of this installer for `simpleviz update`; when run via
  # `curl | bash` there is no file on disk, so fetch it from the repo
  if [ -f "${BASH_SOURCE[0]:-}" ]; then
    cp "${BASH_SOURCE[0]}" "$SIMPLEVIZ_HOME/install.sh"
  else
    curl -fsSL "https://raw.githubusercontent.com/$REPO/main/install.sh" \
      -o "$SIMPLEVIZ_HOME/install.sh" \
      || echo "note: could not store installer copy; 'simpleviz update' will not work" >&2
  fi
  [ -f "$SIMPLEVIZ_HOME/install.sh" ] && chmod +x "$SIMPLEVIZ_HOME/install.sh"
}

write_launcher() {
  mkdir -p "$BIN_DIR"
  cat >"$BIN_DIR/simpleviz" <<'LAUNCHER'
#!/usr/bin/env bash
# simpleviz launcher — generated by install.sh; reinstalling overwrites it.
set -euo pipefail

SIMPLEVIZ_HOME="${SIMPLEVIZ_HOME:-$HOME/.simpleviz}"
API_URL="https://api.github.com/repos/sstoehrm/simpleviz/releases/latest"
MIN_BB="1.3.0"

usage() {
  cat <<USAGE
usage: simpleviz <graph.edn> [new.edn]   serve a graph (two files: compare old -> new)
       simpleviz update                  install the latest release if it is newer
       simpleviz --version               print the installed version
Serves on a random free port between 7370 and 7379.
Try the bundled example: simpleviz "$SIMPLEVIZ_HOME/examples/demo.edn"
USAGE
}

die() { echo "simpleviz: $*" >&2; exit 1; }

check_bb() {
  local have
  command -v bb >/dev/null 2>&1 \
    || die "babashka (bb) not found — install it from https://babashka.org"
  have=$(bb --version | sed -E 's/[^0-9.]//g')
  [ "$(printf '%s\n' "$MIN_BB" "$have" | sort -V | head -n1)" = "$MIN_BB" ] \
    || die "babashka $have is too old (need >= $MIN_BB)"
}

version() { cat "$SIMPLEVIZ_HOME/VERSION" 2>/dev/null || echo "unknown"; }

update() {
  local latest
  latest=$(curl -fsSL "$API_URL" | grep -m1 '"tag_name"' \
           | sed -E 's/.*: *"([^"]+)".*/\1/') \
    || die "could not query the latest release"
  [ -n "$latest" ] || die "could not read the latest release tag"
  if [ "$latest" = "$(version)" ]; then
    echo "simpleviz $(version) is already up to date"
  elif [ -f "$SIMPLEVIZ_HOME/install.sh" ]; then
    echo "updating $(version) -> $latest"
    SIMPLEVIZ_HOME="$SIMPLEVIZ_HOME" bash "$SIMPLEVIZ_HOME/install.sh"
  else
    die "no stored installer at $SIMPLEVIZ_HOME/install.sh — re-run install.sh manually"
  fi
}

port_busy() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }

free_port() {
  local p
  for p in $(shuf -i 7370-7379); do
    port_busy "$p" || { echo "$p"; return 0; }
  done
  return 1
}

serve() {
  local files=() f port pid i
  [ -d "$SIMPLEVIZ_HOME" ] || die "$SIMPLEVIZ_HOME not found — run install.sh first"
  for f in "$@"; do
    [ -f "$f" ] || die "file not found: $f"
    files+=("$(realpath "$f")")
  done
  check_bb
  port=$(free_port) || die "no free port between 7370 and 7379"
  (cd "$SIMPLEVIZ_HOME" && exec bb serve "${files[@]}" --port "$port") &
  pid=$!
  trap 'kill "$pid" 2>/dev/null || true' INT TERM
  for i in $(seq 1 100); do
    port_busy "$port" && break
    kill -0 "$pid" 2>/dev/null || die "server exited before accepting connections"
    sleep 0.1
  done
  echo "simpleviz: http://localhost:$port"
  if command -v xdg-open >/dev/null 2>&1; then
    xdg-open "http://localhost:$port" >/dev/null 2>&1 || true
  fi
  wait "$pid"
}

case "${1:-}" in
  "" | -h | --help) usage ;;
  --version | version) echo "simpleviz $(version)" ;;
  update) update ;;
  *)
    [ "$#" -le 2 ] || { usage >&2; exit 1; }
    serve "$@"
    ;;
esac
LAUNCHER
  chmod +x "$BIN_DIR/simpleviz"
}

main() {
  check_deps
  fetch_release
  install_files
  write_launcher
  case ":$PATH:" in
    *":$BIN_DIR:"*) ;;
    *) echo "note: $BIN_DIR is not on your PATH — add it to use 'simpleviz'" ;;
  esac
  echo "installed simpleviz $TAG"
  echo "  files:    $SIMPLEVIZ_HOME"
  echo "  launcher: $BIN_DIR/simpleviz"
  echo "try: simpleviz $SIMPLEVIZ_HOME/examples/demo.edn"
}

if [ "${SIMPLEVIZ_INSTALL_SOURCED:-}" != "1" ]; then
  main "$@"
fi
```

- [ ] **Step 2: Syntax-check the installer**

Run: `bash -n install.sh`
Expected: no output, exit 0.

- [ ] **Step 3: Syntax-check the generated launcher without installing**

```bash
scratch=$(mktemp -d)
SIMPLEVIZ_INSTALL_SOURCED=1 SIMPLEVIZ_BIN="$scratch" bash -c \
  'source install.sh && write_launcher'
bash -n "$scratch/simpleviz"
rm -rf "$scratch"
```

Expected: `bash -n` silent, exit 0. (This proves the source-guard skips `main` and the heredoc emits valid bash.)

- [ ] **Step 4: If `shellcheck` is installed, run it (informational)**

Run: `command -v shellcheck && shellcheck install.sh || echo "shellcheck not available — skipped"`
Fix real findings; style-only notes may be ignored with a note in the report.

- [ ] **Step 5: Commit**

```bash
git add install.sh
git commit -m "feat: install script with random-port launcher"
```

---

### Task 2: README + end-to-end verification against the real release

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: Task 1's `install.sh` and its `SIMPLEVIZ_HOME`/`SIMPLEVIZ_BIN` overrides.
- Produces: documented install path; verified installer/launcher behavior.

- [ ] **Step 1: README — add the install one-liner**

In "Getting started", BEFORE the existing tarball paragraph ("Grab the latest tarball…"), add:

```markdown
Quickest install (Linux, needs [babashka](https://babashka.org/) + curl):

    curl -fsSL https://raw.githubusercontent.com/sstoehrm/simpleviz/main/install.sh | bash
    simpleviz ~/.simpleviz/examples/demo.edn   # or any graph.edn; picks a free port 7370-7379

`simpleviz --version` prints the installed release; `simpleviz update`
fetches the latest one. The install lives in `~/.simpleviz` (managed by the
installer) plus a launcher in `~/.local/bin`.

Alternatively, run from a tarball by hand:
```

(The existing "Grab the latest tarball…" paragraph follows directly under that lead-in sentence.)

- [ ] **Step 2: e2e install into scratch dirs (real network, real release)**

```bash
scratch=$(mktemp -d)
SIMPLEVIZ_HOME="$scratch/simpleviz-home" SIMPLEVIZ_BIN="$scratch/bin" bash install.sh
ls "$scratch/simpleviz-home"   # expect public/ server/ examples/ bb.edn VERSION install.sh
cat "$scratch/simpleviz-home/VERSION"
```

Expected: install output names the tag; the note about PATH appears (scratch bin is not on PATH); all listed files present.

- [ ] **Step 3: e2e launcher — version, serve, concurrent port pick, update**

```bash
SIMPLEVIZ_HOME="$scratch/simpleviz-home" "$scratch/bin/simpleviz" --version
# expect: simpleviz <tag>

SIMPLEVIZ_HOME="$scratch/simpleviz-home" "$scratch/bin/simpleviz" \
  "$scratch/simpleviz-home/examples/demo.edn" >"$scratch/out1.log" 2>&1 &
L1=$!
sleep 3; grep -o 'http://localhost:737[0-9]' "$scratch/out1.log"
url=$(grep -o 'http://localhost:737[0-9]' "$scratch/out1.log" | head -n1)
curl -s -o /dev/null -w '%{http_code}\n' "$url"          # expect 200

# second concurrent instance must pick a different port
SIMPLEVIZ_HOME="$scratch/simpleviz-home" "$scratch/bin/simpleviz" \
  "$scratch/simpleviz-home/examples/demo.edn" >"$scratch/out2.log" 2>&1 &
L2=$!
sleep 3; grep -o 'http://localhost:737[0-9]' "$scratch/out2.log"  # different port
kill $L1 $L2

SIMPLEVIZ_HOME="$scratch/simpleviz-home" "$scratch/bin/simpleviz" update
# expect: "simpleviz <tag> is already up to date"
rm -rf "$scratch"
```

Expected: both instances print distinct `737x` URLs, the first URL serves HTTP 200, update reports up to date. Record actual output in the report. (No DISPLAY/xdg-open in this environment — the printed-URL path is the one exercised; browser-open is best-effort by design.)

Note: if the latest published release predates the compare feature, two-file serving via the launcher simply reflects that release's capabilities — verify single-file serving only; do not treat that as a failure of this task.

- [ ] **Step 4: Commit**

```bash
git add README.md
git commit -m "docs: one-liner install instructions"
```
