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
  from other origins. Every page, asset and API request requires the
  random session path in the URL printed at startup. Other local users
  and websites cannot use the server without that URL. Anything that
  bypasses this credential, reaches outside the served folder, or lets
  another site or user drive it is a vulnerability.
- Table formulas in md files (`=(sum :a)`), which run in a SCI sandbox
  in a child process of the server: a formula that reads or writes
  files, reaches the network, calls Java, changes the server or
  outlives its child process is a vulnerability.
- `install.sh` and `simpleviz update`, which download and install
  releases.
- `simpleviz export`, which drives a headless Chrome or Chromium.
- The agent plugin (`plugins/simpleviz`): instructions in the skill that
  would lead an assistant to do something unsafe.

The printed URL is a credential for the running server. Share it only
with people or tools that should have access to the served files. Each
server starts with a fresh 256-bit random path; restarting invalidates
its old URL. No authentication cookies are shared between ports.
Responses use `Referrer-Policy: no-referrer`, and debug and crash logs
omit the session path.

Automatic browser opening uses a private redirect file so the session
URL does not appear in the desktop opener's command arguments. Headless
exports control Chrome through private process pipes rather than an
unauthenticated debugging port.

## Verifying a download

Each release's `simpleviz-vX.Y.Z.tar.gz` and `simpleviz.jar` carry a
signed build provenance attestation (SLSA, via Sigstore) made by the
release workflow. Check a downloaded file with the GitHub CLI:

    gh attestation verify simpleviz-vX.Y.Z.tar.gz --repo sstoehrm/simpleviz \
      --signer-workflow sstoehrm/simpleviz/.github/workflows/release.yml

It passes only for a file built by `.github/workflows/release.yml` in this
repository; any other file, or one changed after the build, fails.

`install.sh` (and so `simpleviz update`) checks the tarball itself: it
compares the download's SHA-256 with the digest GitHub lists for the
release asset (`"digest": "sha256:..."` in the release's API entry) and
installs nothing when the two differ or the release lists none. All its
downloads are HTTPS-only, redirects included. `simpleviz update` fetches
the new release's `install.sh` from that release's tag on
raw.githubusercontent.com, over HTTPS only, and that installer then
checks the tarball as above. The digest comes from GitHub, like the
tarball: it catches a corrupted or swapped download, while the
attestation shows where and from what the file was built. To check by
hand:

    gh release download vX.Y.Z --repo sstoehrm/simpleviz --pattern '*.tar.gz'
    gh api repos/sstoehrm/simpleviz/releases/tags/vX.Y.Z \
      --jq '.assets[] | "\(.digest) \(.name)"'
    sha256sum simpleviz-vX.Y.Z.tar.gz
