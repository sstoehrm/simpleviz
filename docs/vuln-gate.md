# Vulnerability gate

Every push to main, every pull request and a weekly schedule build a
CycloneDX SBOM of everything simpleviz ships, scan it with OSV-Scanner, and fail
only on findings that are exploited or likely to be. Everything else is
reported, not blocking.

```
sbom job (per repo)                     gate job (shared: vuln-gate.yml)
  npm lockfile  → cyclonedx-npm  ┐        OSV-Scanner: each SBOM (+ saved images)
  elkjs vendor  → one-line SBOM  ├─ sbom → tools/vuln_gate.clj
  bb.edn deps   → jars → Syft    ┘          ← CISA KEV feed, FIRST EPSS API
                                            ← .vuln-ignore.yaml
                                          → job summary, vuln-gate-report artifact
```

| File | Role |
| --- | --- |
| `.github/workflows/vulns.yml` | this repo's caller: the SBOM job, then the gate |
| `.github/workflows/vuln-gate.yml` | the shared gate (reusable workflow) |
| `.github/actions/sbom-jars` | SBOM for bb.edn / deps.edn / project.clj (no lockfile, no SBOM tool) |
| `tools/vuln_gate.clj` | the policy: reads OSV-Scanner JSON, fetches KEV and EPSS, decides |
| `tools/stage_jars.clj` | stages a resolved classpath's jars for Syft |
| `.vuln-ignore.yaml` | the allowlist |

## The policy

A finding **fails the build** when it is

1. in the [CISA KEV catalog](https://www.cisa.gov/known-exploited-vulnerabilities-catalog), or
2. scored **EPSS >= 0.1 and CVSS >= 7.0** — a 10 % chance of exploitation
   in the next 30 days, and severe if it happens.

Everything else is **report only**: listed in the job summary and in the
`vuln-gate-report` artifact (each OSV-Scanner result plus `gate.json`), but
never failing the build. A finding a current allowlist entry covers is
**suppressed**.

Details that decide edge cases:

- **One finding per vulnerability and package.** OSV-Scanner groups the
  records different databases keep for the same vulnerability (a GHSA and
  a PYSEC entry, say); a group is one finding, shown under its CVE when it
  has one.
- **CVSS** is the group's highest score as OSV-Scanner computes it from
  the records' CVSS vectors, v2 included: an old CVE scored 7.5 in v2 and
  6.5 in v3 counts as 7.5. A finding with none counts as 7.0 when a record
  rates it HIGH or CRITICAL (GHSA does, distro records don't), so a
  missing score cannot hide it.
- **KEV and EPSS know CVEs only.** A finding is looked up by its CVE
  aliases; one with no CVE at all (a bare GHSA ID) can never fail the
  build, only be reported.
- **Both feeds are fetched at job time** and never cached. When either
  can't be read, the gate fails closed (exit 2) instead of passing silently.
- The thresholds are inputs (`epss-min`, `cvss-min`) of the reusable
  workflow; the defaults are the policy above.

`tools/vuln_gate.clj` exits 0 (pass), 1 (a finding fails) or 2 (could not
decide: a missing scan, an unreadable feed or a malformed allowlist).

## Suppressing a finding

Add an entry to `.vuln-ignore.yaml` at the repo root:

```yaml
ignore:
  - id: CVE-2024-12345
    reason: >-
      Only reachable through the XML import, which simpleviz never calls;
      upstream fix tracked in #123.
    expires: 2026-12-31
```

- `id`: the CVE, or any other ID of the finding (GHSA, PYSEC, …), in any
  case. It matches every finding that lists it as an ID or an alias.
- `reason`: why the finding doesn't apply or can wait. It's reviewed with
  the pull request, so make it checkable.
- `expires`: a `YYYY-MM-DD` date; the entry holds through that day. After
  it, the entry suppresses nothing and the finding fails the build again,
  until it's fixed or the entry is renewed with a fresh reason. Keep the
  horizon short (weeks, not years).

All three fields are required; a malformed file fails the gate (exit 2).
The summary lists expired entries and unused ones (no finding matches them
any more), so both can be cleaned up.

Prefer fixing over suppressing: the report's *Fixed in* column names the
versions that close the finding.

## Using it in another repo

Only the SBOM job is per repo. It uploads one artifact of `*.cdx.json`
files; the gate takes it from there. Pin both references to the same
commit of this repo:

```yaml
jobs:
  sbom:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@<sha>
      # … the stack's SBOM steps from below, writing into sbom/ …
      - uses: actions/upload-artifact@<sha>
        with: { name: sbom, path: sbom/, if-no-files-found: error }
  gate:
    needs: sbom
    uses: sstoehrm/simpleviz/.github/workflows/vuln-gate.yml@<sha>
    with:
      sbom-artifact: sbom
```

The SBOM step for each stack, all producing CycloneDX JSON:

| Stack | SBOM step |
| --- | --- |
| npm | `npx --yes @cyclonedx/cyclonedx-npm@6.0.1 --package-lock-only --output-format JSON --output-file sbom/npm.cdx.json` |
| Maven | `mvn -B org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom -DoutputFormat=json` → copy `target/bom.json` |
| Gradle | plugin `id("org.cyclonedx.bom") version "3.5.0"`, `./gradlew cyclonedxBom` → copy `build/reports/cyclonedx/bom.json` |
| Python | in the installed venv: `pipx run --spec cyclonedx-bom==7.5.0 cyclonedx-py environment --of JSON -o sbom/python.cdx.json` |
| Go | `go run github.com/CycloneDX/cyclonedx-gomod/cmd/cyclonedx-gomod@v1.12.0 mod -json -output sbom/go.cdx.json` |
| bb.edn | `uses: sstoehrm/simpleviz/.github/actions/sbom-jars@<sha>` with `output: sbom/bb.cdx.json` (needs babashka) |
| deps.edn | the same, plus `classpath-command: clojure -Spath` |
| Leiningen | the same, plus `classpath-command: lein classpath` |

**Classpath stacks** (bb.edn, deps.edn, project.clj) have no lockfile and no
SBOM tool. `sbom-jars` resolves the full classpath, transitives included,
copies its jars aside and runs Syft over them. Syft names a jar from the
Maven metadata inside it, and some Clojure jars have none (malli, for
one). `tools/stage_jars.clj` gives such a jar's copy a `pom.properties`
built from its path in `~/.m2`, so it isn't silently dropped. Cache
`~/.m2/repository` before the step.

**Container images:** in the SBOM job, `docker save <image> -o image.tar`,
upload the tar as its own artifact, and pass `image-artifact: <name>` to the
gate. OSV-Scanner then scans the final image (OS packages included) too.

## Not covered

- **Libraries built into babashka itself** (Clojure, Jackson, SnakeYAML, …)
  are not in the SBOM. They are part of the bb binary each user installs,
  not something simpleviz ships.
- **git deps and local roots** in deps.edn are source folders, not jars;
  `stage_jars.clj` prints a warning for each one outside the project.
- **Vendored code** without a manifest stays invisible unless the SBOM job
  describes it, as `vulns.yml` does for `public/vendor` (elkjs).
- **An end-of-life OS** in a scanned image is not flagged: no one publishes
  advisories for it any more, so its scan shows only what was known before
  its end of life. Check the base image's support dates by hand.

## Maintenance

- OSV-Scanner (2.7.0) and Syft (1.54.1) are downloaded as release binaries
  and checked against a pinned SHA-256. Renovate does not bump these, so a
  bump is a manual edit of the version and checksum (from the release's
  `osv-scanner_SHA256SUMS` or `syft_*_checksums.txt`) in `vuln-gate.yml` and
  `sbom-jars/action.yml`.
- `npx` pins `@cyclonedx/cyclonedx-npm` itself, not its dependencies, which
  resolve afresh each run. Unlike OSV-Scanner and Syft, this tool isn't
  locked down by checksum.
- OSV-Scanner asks the OSV API (api.osv.dev) at scan time, so there is no
  vulnerability DB to cache; resolved deps are cached per `bb.edn` hash
  (`~/.m2/repository`). The scan step fails on any OSV-Scanner exit code
  but 0 (clean) and 1 (findings), e.g. 128 for an SBOM with no packages.
- The gate script's tests run with the rest: `bb test:clj`
  (`test/vuln_gate_test.clj`, `test/stage_jars_test.clj`).
- Run the gate locally on any OSV-Scanner JSON:

  ```sh
  osv-scanner scan source --format json --output-file scan.json -L sbom.cdx.json
  bb tools/vuln_gate.clj --allowlist .vuln-ignore.yaml scan.json
  ```

  Options go before the reports; `--kev` and `--epss` take local files for
  offline runs.
