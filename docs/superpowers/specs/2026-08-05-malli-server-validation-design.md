# simpleviz: server-side validation with malli

Date: 2026-08-05
Status: approved
Origin: PR #2 review comment on `src/simpleviz/validate.cljs` — "Do this with malli".
Constraint discovered: malli cannot run under Squint (needs real CLJS semantics);
it runs fine under babashka (verified with malli 0.19.1). User chose full
server-side validation.

## Decision

All graph validation and normalization moves from the Squint frontend into the
babashka server, using malli for structural (shape) checks and plain Clojure for
the semantic normalization. The frontend becomes a pure view over an
already-normalized graph.

## Server

- `bb.edn` gains top-level `:deps {metosin/malli {:mvn/version "0.19.1"}}`.
- New `server/graph.clj` (ns `graph`): `normalize` takes parsed EDN and returns
  `{:nodes {name {:id :name :type :attrs}} :edges [..] :boxes [..]
    :parent-of {..} :warnings [..]}` — the exact shape the frontend consumed
  before, minus `:boxes-by-name` (client derives it in one reduce).
- Shape checks via per-element malli schemas, lenient: a malformed element
  produces a `malli.error/humanize`-based warning and is skipped — never a
  whole-file rejection. Semantic rules unchanged from the previous frontend
  behavior: direction swap for `:<-`, arrows map, string coercion of name/type,
  first-box-wins membership, self-containment rejection, cycle breaking,
  duplicate box names, prefixed component ids (`n:`/`b:`).
- Because the server sees real EDN (not JSON), two input forms get MORE liberal:
  `:direction` accepts keyword `:->` or string `"->"`; box `:components` accepts
  sets or vectors natively. Warning texts change to humanized malli wording for
  shape violations (documented deviation; counts and semantics preserved).
- `serve.clj`: `/api/graph` returns the normalized graph JSON via a pure,
  testable `graph-json` fn; parse errors still `{"error": ...}` with status 200.
  The old `edn->json` passthrough is removed.

## Frontend

- Delete `src/simpleviz/validate.cljs` and `test/simpleviz/validate_test.cljs`.
- `app.cljs`: `/api/graph` response is the normalized graph; check `:error`,
  derive `:boxes-by-name` from `:boxes`, pass to `to-elk`/`graph-view`
  unchanged. `transform.cljs`/`render.cljs` untouched.
- `transform_test.cljs`/`layout_test.cljs` no longer call `validate`; they build
  normalized-graph fixtures directly (better isolation).

## Tests

- The ~20 validation tests port to `test/graph_test.clj` (clojure.test, run by
  `bb test:clj`): same cases and warning counts, EDN inputs with keywords/sets,
  plus new cases for string directions, set components, and humanized shape
  warnings.
- `test/server_test.clj` updated: EDN→JSON conversion assertions now target
  `serve/graph-json` output (normalized shape), parse-error case unchanged.

## Out of scope

- No API surface changes beyond the `/api/graph` payload shape.
- README needs only a one-line touch (validation now server-side via malli).
