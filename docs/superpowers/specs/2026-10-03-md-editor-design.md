# Markdown editor — design

An element's `:md-ref` names the markdown doc that describes it. Today
it only draws a dotted border. This adds a plain-text editor to the
viewer: open the doc from the selected element, edit it, save it to
disk. No markdown rendering.

Figure: `.blend/specs/2026-10-03-md-editor.edn` (serve it with
`simpleviz`).

## Goal

    ;; graph.edn
    {:nodes {:api {:name "API" :md-ref "docs/api.md"}}}

Selecting API and pressing `f m` (or "open md" in the toolbar) opens
`docs/api.md` in a panel on the right, wider than the inspector. A
button expands it to cover the window. Ctrl/Cmd+S or Save writes the
file; closing the panel saves too. A missing file opens empty and is
created on the first save. An agent writing the same file while it is
open never gets silently overwritten.

Out of scope: markdown rendering or preview, syntax highlighting,
opening files that no `:md-ref` names, editing in PNG/SVG-served
sessions, a server-side undo stack.

## Behavior

### Which files

- The `:md-ref` value is a non-blank string on a node or box (the same
  rule as the dotted border). It is resolved against the graph file it
  is in, with the `:ref` rules: relative path, `..` allowed, never above
  the folder of the served root file.
- Only `.md` files (extension compared case-insensitively). Anything
  else is refused with an error banner.
- Compare mode: `.md` files are never forked, and the original and its
  fork live in the same folder, so both sides name the same file. The
  editor always opens that one file.
- Available only where follow-ref is: an EDN-served session (the graph
  payload carries `:path`). PNG/SVG-served sessions show no "open md".

### Panel

- `#md-panel` docks on the right and replaces the inspector while open:
  width `max(420px, 45vw)`, full height below the top bar.
- Header: the root-relative path, `●` while there are unsaved changes,
  `new file` while the file does not exist, then buttons fullscreen
  (`⤢`), Save, Close (`×`).
- Body: one `<textarea>` filling the panel, monospace, soft-wrapped
  (display only — no line breaks are added to the text).
- Fullscreen: the panel covers the whole window; `⤢` or Esc returns to
  the docked panel. Esc never closes the panel.
- The panel is tied to its file, not to the selection: selecting other
  elements, following refs and pairs, and navigating the trail leave it
  open. While it is open the inspector is hidden.
- Opening "open md" on an element whose doc is already open focuses the
  textarea. Opening a different doc saves the current one first (see
  Saving); if that save fails, the current doc stays open.

### Saving

- Save triggers: Ctrl/Cmd+S (whenever the panel is open, also with the
  textarea focused; the browser's own save dialog is suppressed), the
  Save button, Close, and opening a different doc.
- Save with no unsaved changes and an existing file does nothing. Save
  on a new file with empty text creates the empty file (an explicit
  Save) — but Close on an untouched new file does not create it.
- A save sends the text and the `version` it was based on. The server
  writes only when the file on disk still has that version (see
  Server). On success the panel takes the new version and clears `●`;
  Close then closes.
- On a refused save the panel stays open with an error banner inside
  it: "changed on disk" (conflict, see Polling) or "locked by X" (an
  agent holds the file's lock — retry later) or the server's message.
- Line endings: the textarea turns `\r\n` into `\n`. When the text
  loaded from disk contained `\r\n`, the save converts every `\n` back
  to `\r\n`.
- Closing or reloading the browser tab with unsaved changes triggers
  the browser's "leave page?" prompt (`beforeunload`); a save cannot
  run reliably while the tab unloads.

### Polling and conflicts

- While the panel is open, the page's existing 1 s tick also fetches
  the doc (`GET /api/text`).
- Version unchanged: nothing happens.
- Version changed, no unsaved changes: the textarea takes the disk text
  (caret kept at the same offset, clamped to the new length).
- Version changed, unsaved changes: the panel shows a conflict banner,
  "changed on disk", with two buttons:
  - **Reload** discards the local edits and takes the disk text.
  - **Overwrite** saves the local text over the disk version: a save
    whose base is the version the conflicting fetch returned (kept in
    the conflict state), not the one the edit started from.
  The banner stays until one is chosen or the disk text happens to
  equal the local text again.
- A doc deleted on disk while open turns into `new file` (unsaved
  changes are kept and marked `●`).
- No fetch starts while a save is in flight, and a fetch that started
  before a save finished is discarded (a generation counter, as the
  graph reload already does), so a save never reads back as its own
  conflict.
- A failed fetch (server gone) leaves the panel as it is; the page's
  existing disconnected banner covers it.

### Keys

- `f m` — open md: on a node or box with a string `:md-ref`, in an
  editable session. Listed in the chord table, the toolbar hint and the
  help.
- In the textarea, chords, `?` and Ctrl+Z already pass through to the
  textarea (the page skips them while a text field has focus), so its
  native undo works. Escape still cancels pick modes and the export
  menu as today, and additionally leaves fullscreen.

## Architecture

### Server: `server/serve.clj`

- `resolve-path` takes the allowed extensions as an argument;
  `ref-extensions` (`#{"edn" "png" "svg"}`) stays the default for graph
  files and `#{"md"}` is used for docs. The error names the allowed
  set ("... is not an .md file").
- `GET /api/text?path=<root-relative .md>` →
  `{"text": .., "version": .., "exists": true|false}`.
  - `version` is the hex SHA-1 of the file's bytes; a missing file has
    `exists: false`, `text: ""` and `version: null`.
  - Files over 1 MiB are refused (checked by size before reading).
  - Errors (refused path, too large, unreadable) answer
    `{"error": msg}` with status 200, like `/api/graph`.
  - Text is read and written as UTF-8.
- `POST /api/text/save` with JSON `{"path", "text", "base"}` (`base` is
  the version the edit started from, `null` for a new file), behind
  `post-only` and `edit-guard` like the other writes.
  - 400 `{error}`: malformed body, refused path, text over 1 MiB, or a
    PNG/SVG-served session ("PNG and SVG sources are read-only" — the
    page never offers the editor there, the server enforces it).
  - 409 `{error: "locked by X"}`: a live lock on the file.
  - 409 `{error: "changed on disk", text, version, exists}`: the file's
    current version is not `base` (including: it now exists and `base`
    is null, or it is gone and `base` is not null).
  - 200 `{version}` otherwise, after writing: missing parent folders
    are created, the text goes to a temp file in the same folder, then
    an atomic move replaces the target.
  - The version check and the write run under one server-wide
    `locking` monitor, so two saves cannot interleave.
  - Every save is logged with `log/event!`, like edits.
- `/api/lock` and `/api/unlock` resolve with `ref-extensions ∪ #{"md"}`,
  so an agent can lock a doc before writing it.
- No server-side undo stack for docs.

### Page

- `editor.cljs` (pure, unit-tested):
  - `md-target [current-path sel]` — nil when the selection has no
    string `:md-ref`; `{:path p}` (root-relative, via `resolve-ref`
    against `current-path`) for an `.md` file under the root; else
    `{:error msg}` ("leaves the served folder" / "is not an .md file"),
    shown as a banner. The toolbar action shows for any string
    `:md-ref`, so a bad one explains itself instead of hiding.
  - `from-disk [text]` / `to-disk [text crlf?]` — the CRLF round-trip.
  - `md-dirty? [md]` — the text differs from the last loaded or saved
    text (`:saved`; nil after the file vanished, so anything is dirty).
  - `adopt-doc [md fetched]` — the panel state after taking a fetched
    doc as the disk state; `gone-doc [md]` — after the file vanished.
  - `poll-outcome [md fetched]` — `"same"`, `"take"`, `"conflict"` or
    `"gone"`.
  - `"open-md"` in the chord table (`f m`, node and box).
- `app.cljs`:
  - State `:md` — nil when closed, else `{:path :text :saved :base
    :exists :crlf :conflict :error :full :saving}`; `:conflict` holds
    the fetched `{:text :version :exists}`. A module-level generation
    counter, bumped by every open and save, discards stale polls.
  - `"open-md"` in `action-spec` and `toolbar-actions` (node, box).
  - `open-md!`, `save-md!` (returns a promise of success), `close-md!`
    (save, then close on success), and the tick extension.
  - `md-panel` view, rendered instead of `details-view` while `:md` is
    set; a `fullscreen` class switches its CSS.
  - Ctrl/Cmd+S and `beforeunload` listeners.
- `public/style.css`: `#md-panel` docked and fullscreen styles, both
  themes via the existing CSS variables.

## Docs

- SKILL.md: the `:md-ref` line says the user can open the doc in the
  viewer's text editor (`f m`), and that an agent writing a doc that is
  open there should take the lock on its path (`/api/lock` with the
  `.md` path); the user's save is refused while it holds it, and an
  unlocked write shows the user a conflict instead of being
  overwritten.
- `docs/guide.md`: the attribute line, the keys table (`f m`), and a
  short "Editing docs" section.
- In-app help: the Edit and Keys sections.
- README: the data-format comment for `:md-ref`.

## Testing

- `test/server_test.clj`, against a served temp folder:
  - GET: existing file (text, version, exists), missing file, `..`
    escape, absolute path, symlink out of the folder, non-`.md`
    extension, over-size file.
  - Save: create with missing folders, update with the right base,
    409 on a stale base (file changed / created / deleted), 409 on a
    held lock, 403 on a foreign Origin, 415 without JSON, 405 on GET,
    400 on a bad path; the file content after each.
  - Lock routes accept a `.md` path.
- `test/simpleviz/editor_test.cljs`: `md-target` (plain, `..`, escape,
  non-md, none), `poll-outcome` (each outcome), `adopt-doc`,
  `gone-doc`, `md-dirty?`, `from-disk`/`to-disk` (LF and CRLF), the
  `f m` chord for node, box and edge.
- End-to-end in a real browser (`dev/cdp.mjs` or the browser tools):
  open a doc, edit, Ctrl+S, file on disk changed; change the file on
  disk while clean (textarea updates) and while dirty (conflict
  banner, Reload and Overwrite); fullscreen and back; Close saves; a
  missing file is created on save.
