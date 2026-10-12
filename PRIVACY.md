# Privacy

simpleviz collects no data. It has no accounts, no analytics and no
telemetry, and there is no simpleviz service that anything could be
sent to.

## What runs where

- **The server and the page** (`simpleviz <file>`) run on your machine.
  The server listens on 127.0.0.1 only and serves the files of the
  folder you started it in; the page loads its scripts and styles from
  it, nothing from elsewhere.
- **Your choices** (theme, layout) are kept in `~/.simpleviz/prefs.edn`
  (or `$SIMPLEVIZ_HOME`). With `--debug`, edits and errors are logged to
  `~/.simpleviz/logs`; a crash writes a report there. None of it leaves
  your machine.
- **`simpleviz export`** starts your own Chrome or Chromium, headless,
  and talks to it over 127.0.0.1.

## When it goes online

- **Installing and updating** (`install.sh`, `simpleviz update`) asks
  GitHub's API for the latest release and downloads it from GitHub.
  GitHub's [privacy statement](https://docs.github.com/en/site-policy/privacy-policies/github-general-privacy-statement)
  applies to those requests.
- **An md page with a web image** (`![x](https://…)`) loads that image
  from its site, as any markdown viewer does; links open only when you
  click them.

## The agent plugin

The plugin is instructions for an AI assistant (Claude Code, Codex,
Grok). It sends nothing itself, but the assistant you use reads your
files and prompts under its own provider's privacy policy.

Questions: open an issue on
[GitHub](https://github.com/sstoehrm/simpleviz/issues).
