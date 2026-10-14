# Security policy

## Reporting a vulnerability

Please don't open a public issue for a security problem. Report it
privately instead:
[**Report a vulnerability**](https://github.com/sstoehrm/simpleviz/security/advisories/new)
(the Security tab of this repository). The report stays visible only to
you and the maintainer until a fix is out.

Helpful to include: the simpleviz version (`simpleviz --version`), how
you installed it (install.sh, bbin, the plugin), and the steps or the
file that shows the problem.

## Supported versions

Only the [latest release](https://github.com/sstoehrm/simpleviz/releases/latest)
gets security fixes; `simpleviz update` installs it.

## In scope

- The local server (`simpleviz <file>`): it listens on 127.0.0.1 only,
  reads and writes files below the served folder, and refuses writes
  from other origins. Anything that reaches outside that folder, or
  lets another site or user drive it, is a vulnerability.
- Table formulas in md files (`=(sum :a)`), which the server runs in a
  SCI sandbox: a formula that reads or writes files, reaches the
  network or calls Java is a vulnerability. One that only keeps a CPU
  busy is a known limit.
- `install.sh` and `simpleviz update`, which download and install
  releases.
- `simpleviz export`, which drives a headless Chrome or Chromium.
- The agent plugin (`plugins/simpleviz`): instructions in the skill that
  would lead an assistant to do something unsafe.
