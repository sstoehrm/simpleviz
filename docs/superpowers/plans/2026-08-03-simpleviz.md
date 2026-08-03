# simpleviz Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A babashka-served browser tool that renders an EDN-described graph (nodes, directed edges, nested grouping boxes) as an interactive SVG diagram with live reload and a details sidebar.

**Architecture:** A dumb babashka http-kit server parses the EDN file and serves it as JSON plus static files. All logic runs in the browser as plain ES modules (no build step): validate → transform to ELK graph → ELK.js layered layout → SVG render. Pure modules (colors, validate, transform) are tested with `node --test`; the server's EDN→JSON conversion with `bb test`.

**Tech Stack:** babashka (http-kit, cheshire — both built in), ELK.js 0.9.3 (vendored `elk.bundled.js`), vanilla ES modules + SVG, `node --test`, `clojure.test`.

**Spec:** `docs/superpowers/specs/2026-08-03-simpleviz-design.md` — read it before starting any task.

## Global Constraints

- No build step, no npm, no package.json. Frontend is plain ES modules loaded directly.
- No dependencies beyond babashka built-ins and the vendored `elk.bundled.js`.
- Pure logic modules (`colors.mjs`, `validate.mjs`, `transform.mjs`) must not touch the DOM — they are imported by `node --test`.
- Server port: 8080. Poll interval: 1000 ms. Color table size: 255. Golden angle: 137.508°.
- ELK ids are namespaced: node `n:<name>`, box `b:<name>` — names may collide across kinds.
- Bad input never yields a blank page: parse errors → error banner, validation issues → warning banner + element skipped.
- Verification commands: `bb test` (runs Clojure + JS tests), `node --test test/` (JS only).
- Commit messages end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: Color tables and type→color assignment

**Files:**
- Create: `public/lib/colors.mjs`
- Test: `test/colors.test.mjs`

**Interfaces:**
- Consumes: nothing (first task).
- Produces (used by Task 6's `app.js`):
  - `fnv1a(str) -> number` — 32-bit unsigned FNV-1a hash.
  - `assignIndices(types: string[]) -> Map<string, number>` — deterministic type→index (0–254) with linear probing on collision; ignores empty strings; order-independent.
  - `colorMap(types: string[], table: any[]) -> Map<string, any>` — type→table entry.
  - `NODE_TABLE: string[255]` — hsl color strings.
  - `BOX_TABLE: {border: string, fill: string}[255]`.
  - `NEUTRAL_NODE: string`, `NEUTRAL_BOX: {border, fill}` — for empty/missing type.

- [ ] **Step 1: Write the failing test**

Create `test/colors.test.mjs`:

```js
import {test} from "node:test";
import assert from "node:assert/strict";
import {
  fnv1a, assignIndices, colorMap,
  NODE_TABLE, BOX_TABLE, NEUTRAL_NODE, NEUTRAL_BOX,
} from "../public/lib/colors.mjs";

test("fnv1a is deterministic and unsigned", () => {
  assert.equal(fnv1a("service"), fnv1a("service"));
  assert.notEqual(fnv1a("service"), fnv1a("database"));
  assert.ok(fnv1a("service") >= 0);
});

test("tables have 255 entries", () => {
  assert.equal(NODE_TABLE.length, 255);
  assert.equal(BOX_TABLE.length, 255);
  assert.match(NODE_TABLE[0], /^hsl\(/);
  assert.match(BOX_TABLE[0].border, /^hsl\(/);
  assert.match(BOX_TABLE[0].fill, /\/ 0\.1\)$/);
});

test("assignment is independent of input order", () => {
  const a = assignIndices(["db", "service", "cache"]);
  const b = assignIndices(["cache", "db", "service"]);
  assert.deepEqual(
    [...a.entries()].sort(),
    [...b.entries()].sort(),
  );
});

test("empty types are ignored", () => {
  const idx = assignIndices(["", "svc", ""]);
  assert.equal(idx.size, 1);
  assert.ok(idx.has("svc"));
});

test("up to 255 types all get distinct indices", () => {
  const types = Array.from({length: 255}, (_, i) => `type-${i}`);
  const idx = assignIndices(types);
  assert.equal(new Set(idx.values()).size, 255);
});

test("more than 255 types does not hang; extras reuse slots", () => {
  const types = Array.from({length: 300}, (_, i) => `type-${i}`);
  const idx = assignIndices(types);
  assert.equal(idx.size, 300);
});

test("hash collision probes to the next free slot", () => {
  // find a second string whose hash lands on the same slot as "alpha"
  const target = fnv1a("alpha") % 255;
  let other = null;
  for (let i = 0; other === null && i < 1000000; i++) {
    const cand = "t" + i;
    if (cand !== "alpha" && fnv1a(cand) % 255 === target) other = cand;
  }
  assert.ok(other, "no colliding string found");
  const idx = assignIndices(["alpha", other]);
  assert.notEqual(idx.get("alpha"), idx.get(other));
});

test("colorMap maps types to table entries", () => {
  const m = colorMap(["svc"], NODE_TABLE);
  assert.match(m.get("svc"), /^hsl\(/);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test test/`
Expected: FAIL — cannot find module `../public/lib/colors.mjs`.

- [ ] **Step 3: Write the implementation**

Create `public/lib/colors.mjs`:

```js
// Fixed 255-entry color tables. Entry i uses hue i * golden angle, so
// ADJACENT indices are visually distinct — that makes linear probing on
// hash collision a safe "next best" choice.

const TABLE_SIZE = 255;
const GOLDEN_ANGLE = 137.508;

export function fnv1a(str) {
  let h = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

function hue(i) {
  return ((i * GOLDEN_ANGLE) % 360).toFixed(1);
}

export const NODE_TABLE = Array.from({length: TABLE_SIZE}, (_, i) =>
  `hsl(${hue(i)} 65% 38%)`);

export const BOX_TABLE = Array.from({length: TABLE_SIZE}, (_, i) => ({
  border: `hsl(${hue(i)} 45% 55%)`,
  fill: `hsl(${hue(i)} 45% 55% / 0.1)`,
}));

export const NEUTRAL_NODE = "hsl(0 0% 40%)";
export const NEUTRAL_BOX = {border: "hsl(0 0% 65%)", fill: "hsl(0 0% 65% / 0.1)"};

export function assignIndices(types) {
  const sorted = [...new Set(types)].filter(t => t).sort();
  const taken = new Set();
  const result = new Map();
  for (const t of sorted) {
    let idx = fnv1a(t) % TABLE_SIZE;
    if (taken.size >= TABLE_SIZE) {
      result.set(t, idx); // table exhausted: reuse
      continue;
    }
    while (taken.has(idx)) idx = (idx + 1) % TABLE_SIZE;
    taken.add(idx);
    result.set(t, idx);
  }
  return result;
}

export function colorMap(types, table) {
  const m = new Map();
  for (const [t, i] of assignIndices(types)) m.set(t, table[i]);
  return m;
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test test/`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add public/lib/colors.mjs test/colors.test.mjs
git commit -m "feat: type color tables with golden-angle hues and probing"
```

---

### Task 2: Input validation and normalization

**Files:**
- Create: `public/lib/validate.mjs`
- Test: `test/validate.test.mjs`

**Interfaces:**
- Consumes: raw graph JSON as produced by the server — `{nodes: {name: attrs}, edges: [attrs], boxes: [attrs]}`, keywords already strings (`:->` arrived as `"->"`), sets as arrays.
- Produces `validate(raw) -> graph` where graph is:
  - `nodes: Map<string, {id, name, type, attrs}>` — `name` falls back to the map key, `type` to `""`, `attrs` is the original map.
  - `edges: [{id: "e"+i, source, target, arrows: {source: bool, target: bool}, name, type, attrs}]` — `:<-` already normalized by swapping endpoints.
  - `boxes: [{id: "b:"+name, name, type, components: string[], attrs}]` — components are **prefixed ids** (`n:x` or `b:x`), only valid, deduplicated memberships.
  - `boxesByName: Map<string, box>`
  - `parentOf: Map<string, string>` — prefixed component id → owning box **name**.
  - `warnings: string[]`

- [ ] **Step 1: Write the failing test**

Create `test/validate.test.mjs`:

```js
import {test} from "node:test";
import assert from "node:assert/strict";
import {validate} from "../public/lib/validate.mjs";

const base = () => ({
  nodes: {a: {name: "A", type: "svc"}, b: {name: "B"}},
  edges: [],
  boxes: [],
});

test("empty input yields empty graph, no warnings", () => {
  const g = validate({});
  assert.equal(g.nodes.size, 0);
  assert.deepEqual(g.edges, []);
  assert.deepEqual(g.boxes, []);
  assert.deepEqual(g.warnings, []);
});

test("node name falls back to its key; type to empty string", () => {
  const g = validate({nodes: {a: {}}});
  assert.equal(g.nodes.get("a").name, "a");
  assert.equal(g.nodes.get("a").type, "");
});

test("direction -> keeps order, arrow on target only", () => {
  const raw = base();
  raw.edges = [{nodes: ["a", "b"], direction: "->", name: "x", type: "t"}];
  const g = validate(raw);
  assert.equal(g.edges[0].source, "a");
  assert.equal(g.edges[0].target, "b");
  assert.deepEqual(g.edges[0].arrows, {source: false, target: true});
});

test("direction <- swaps endpoints", () => {
  const raw = base();
  raw.edges = [{nodes: ["a", "b"], direction: "<-"}];
  const g = validate(raw);
  assert.equal(g.edges[0].source, "b");
  assert.equal(g.edges[0].target, "a");
  assert.deepEqual(g.edges[0].arrows, {source: false, target: true});
});

test("<-> arrows both ends; missing direction means none", () => {
  const raw = base();
  raw.edges = [{nodes: ["a", "b"], direction: "<->"}, {nodes: ["a", "b"]}];
  const g = validate(raw);
  assert.deepEqual(g.edges[0].arrows, {source: true, target: true});
  assert.deepEqual(g.edges[1].arrows, {source: false, target: false});
});

test("unknown direction warns and renders undirected", () => {
  const raw = base();
  raw.edges = [{nodes: ["a", "b"], direction: "=>"}];
  const g = validate(raw);
  assert.equal(g.edges.length, 1);
  assert.deepEqual(g.edges[0].arrows, {source: false, target: false});
  assert.equal(g.warnings.length, 1);
});

test("edge to unknown node is skipped with warning", () => {
  const raw = base();
  raw.edges = [{nodes: ["a", "ghost"], direction: "->"}];
  const g = validate(raw);
  assert.equal(g.edges.length, 0);
  assert.match(g.warnings[0], /ghost/);
});

test("edge :nodes must be a 2-element vector", () => {
  const raw = base();
  raw.edges = [{nodes: ["a"]}, {nodes: "ab"}, {}];
  const g = validate(raw);
  assert.equal(g.edges.length, 0);
  assert.equal(g.warnings.length, 3);
});

test("box components become prefixed ids", () => {
  const raw = base();
  raw.boxes = [{name: "x", components: ["a", "b"]}];
  const g = validate(raw);
  assert.deepEqual(g.boxesByName.get("x").components.sort(), ["n:a", "n:b"]);
  assert.equal(g.parentOf.get("n:a"), "x");
});

test("boxes nest via box-name components", () => {
  const raw = base();
  raw.boxes = [
    {name: "outer", components: ["inner"]},
    {name: "inner", components: ["a"]},
  ];
  const g = validate(raw);
  assert.deepEqual(g.boxesByName.get("outer").components, ["b:inner"]);
  assert.equal(g.parentOf.get("b:inner"), "outer");
});

test("duplicate membership: first box in file order wins", () => {
  const raw = base();
  raw.boxes = [
    {name: "x", components: ["a"]},
    {name: "y", components: ["a", "b"]},
  ];
  const g = validate(raw);
  assert.equal(g.parentOf.get("n:a"), "x");
  assert.deepEqual(g.boxesByName.get("y").components, ["n:b"]);
  assert.equal(g.warnings.length, 1);
});

test("unknown component ignored with warning", () => {
  const raw = base();
  raw.boxes = [{name: "x", components: ["ghost"]}];
  const g = validate(raw);
  assert.deepEqual(g.boxesByName.get("x").components, []);
  assert.match(g.warnings[0], /ghost/);
});

test("box cannot contain itself", () => {
  const raw = base();
  raw.boxes = [{name: "x", components: ["x", "a"]}];
  const g = validate(raw);
  assert.deepEqual(g.boxesByName.get("x").components, ["n:a"]);
  assert.equal(g.warnings.length, 1);
});

test("containment cycle is broken with warning", () => {
  const raw = base();
  raw.boxes = [
    {name: "x", components: ["y"]},
    {name: "y", components: ["x"]},
  ];
  const g = validate(raw);
  const links = [g.parentOf.get("b:x"), g.parentOf.get("b:y")].filter(v => v !== undefined);
  assert.equal(links.length, 1);
  assert.ok(g.warnings.length >= 1);
});

test("duplicate box name: later one ignored", () => {
  const raw = base();
  raw.boxes = [{name: "x", components: ["a"]}, {name: "x", components: ["b"]}];
  const g = validate(raw);
  assert.equal(g.boxes.length, 1);
  assert.equal(g.warnings.length, 1);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test test/`
Expected: FAIL — cannot find module `../public/lib/validate.mjs` (colors tests still pass).

- [ ] **Step 3: Write the implementation**

Create `public/lib/validate.mjs`:

```js
// Normalizes the raw server JSON into a validated graph structure.
// Never throws on bad data — problems become entries in `warnings`
// and the offending element is skipped.

const DIRECTIONS = new Set(["->", "<-", "<->", "-"]);

export function validate(raw) {
  const warnings = [];

  const nodes = new Map();
  for (const [key, val] of Object.entries(raw.nodes || {})) {
    const attrs = val && typeof val === "object" ? val : {};
    nodes.set(key, {
      id: key,
      name: attrs.name || key,
      type: attrs.type || "",
      attrs,
    });
  }

  const edges = [];
  (raw.edges || []).forEach((e, i) => {
    const ends = e.nodes;
    if (!Array.isArray(ends) || ends.length !== 2) {
      warnings.push(`edge ${i}: :nodes must be a vector of exactly 2 node names`);
      return;
    }
    const missing = ends.filter(n => !nodes.has(n));
    if (missing.length) {
      warnings.push(`edge ${i} [${ends.join(" ")}]: unknown node(s): ${missing.join(", ")}`);
      return;
    }
    let dir = e.direction === undefined ? "-" : e.direction;
    if (!DIRECTIONS.has(dir)) {
      warnings.push(`edge ${i}: unknown direction "${dir}", treating as undirected`);
      dir = "-";
    }
    let [source, target] = ends;
    if (dir === "<-") [source, target] = [target, source];
    edges.push({
      id: `e${i}`,
      source,
      target,
      arrows: {source: dir === "<->", target: dir !== "-"},
      name: e.name || "",
      type: e.type || "",
      attrs: e,
    });
  });

  const boxes = [];
  const boxesByName = new Map();
  (raw.boxes || []).forEach((b, i) => {
    if (!b || !b.name) {
      warnings.push(`box ${i}: missing :name, skipped`);
      return;
    }
    if (boxesByName.has(b.name)) {
      warnings.push(`box "${b.name}": duplicate name, later definition skipped`);
      return;
    }
    const box = {
      id: `b:${b.name}`,
      name: b.name,
      type: b.type || "",
      components: [...(b.components || [])],
      attrs: b,
    };
    boxes.push(box);
    boxesByName.set(b.name, box);
  });

  // Resolve memberships. ELK needs a strict hierarchy, so each component
  // may belong to at most one box — first box in file order wins.
  const parentOf = new Map();
  for (const box of boxes) {
    const kept = [];
    for (const c of box.components) {
      const isNode = nodes.has(c);
      const isBox = boxesByName.has(c);
      if (!isNode && !isBox) {
        warnings.push(`box "${box.name}": unknown component "${c}"`);
        continue;
      }
      if (isNode && isBox) {
        warnings.push(`"${c}" names both a node and a box; box "${box.name}" gets the node`);
      }
      if (isBox && !isNode && c === box.name) {
        warnings.push(`box "${box.name}" cannot contain itself`);
        continue;
      }
      const id = isNode ? `n:${c}` : `b:${c}`;
      if (parentOf.has(id)) {
        warnings.push(`"${c}" is already in box "${parentOf.get(id)}"; membership in "${box.name}" ignored`);
        continue;
      }
      parentOf.set(id, box.name);
      kept.push(id);
    }
    box.components = kept;
  }

  // Break containment cycles (a in b, b in a) by detaching one link.
  for (const box of boxes) {
    const seen = new Set([box.name]);
    let p = parentOf.get(`b:${box.name}`);
    while (p !== undefined) {
      if (seen.has(p)) {
        const parentName = parentOf.get(`b:${box.name}`);
        warnings.push(`box containment cycle: detaching "${box.name}" from "${parentName}"`);
        const parent = boxesByName.get(parentName);
        parent.components = parent.components.filter(c => c !== `b:${box.name}`);
        parentOf.delete(`b:${box.name}`);
        break;
      }
      seen.add(p);
      p = parentOf.get(`b:${p}`);
    }
  }

  return {nodes, edges, boxes, boxesByName, parentOf, warnings};
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test test/`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add public/lib/validate.mjs test/validate.test.mjs
git commit -m "feat: graph validation and normalization with warnings"
```

---

### Task 3: Transform validated graph to ELK input

**Files:**
- Create: `public/lib/transform.mjs`
- Test: `test/transform.test.mjs`

**Interfaces:**
- Consumes: the validated graph from Task 2 (`nodes`, `boxes`, `boxesByName`, `parentOf`, `edges`).
- Produces: `toElk(graph, measure) -> elkGraph` where `measure(text, cssFont) -> pixelWidth`. The elk graph is `{id: "root", layoutOptions, children, edges}` with node children `{id: "n:x", width, height}` and box children `{id: "b:x", layoutOptions, children}`. Also exports `NODE_FONT` and `SUB_FONT` (css font strings, reused by render).

- [ ] **Step 1: Write the failing test**

Create `test/transform.test.mjs`:

```js
import {test} from "node:test";
import assert from "node:assert/strict";
import {validate} from "../public/lib/validate.mjs";
import {toElk} from "../public/lib/transform.mjs";

const measure = (text) => text.length * 7;

test("node sizing uses label widths; typed nodes are taller", () => {
  const g = validate({nodes: {a: {name: "Hello", type: "svc"}, b: {}}});
  const elk = toElk(g, measure);
  const a = elk.children.find(c => c.id === "n:a");
  const b = elk.children.find(c => c.id === "n:b");
  assert.ok(a.width >= measure("Hello"));
  assert.equal(a.height, 44);
  assert.equal(b.height, 30);
});

test("boxes nest components; contained elements not repeated at root", () => {
  const g = validate({
    nodes: {a: {}, b: {}},
    boxes: [
      {name: "outer", components: ["inner", "a"]},
      {name: "inner", components: ["b"]},
    ],
  });
  const elk = toElk(g, measure);
  assert.deepEqual(elk.children.map(c => c.id), ["b:outer"]);
  const outer = elk.children[0];
  assert.deepEqual(outer.children.map(c => c.id).sort(), ["b:inner", "n:a"]);
  const inner = outer.children.find(c => c.id === "b:inner");
  assert.deepEqual(inner.children.map(c => c.id), ["n:b"]);
  assert.ok(outer.layoutOptions["elk.padding"].includes("top=40"));
});

test("edges use prefixed ids and live at the root", () => {
  const g = validate({
    nodes: {a: {}, b: {}},
    edges: [{nodes: ["a", "b"], direction: "->"}],
  });
  const elk = toElk(g, measure);
  assert.deepEqual(elk.edges, [{id: "e0", sources: ["n:a"], targets: ["n:b"]}]);
});

test("root layout options select hierarchical layered layout", () => {
  const elk = toElk(validate({}), measure);
  assert.equal(elk.layoutOptions["elk.algorithm"], "layered");
  assert.equal(elk.layoutOptions["elk.direction"], "RIGHT");
  assert.equal(elk.layoutOptions["elk.hierarchyHandling"], "INCLUDE_CHILDREN");
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test test/`
Expected: FAIL — cannot find module `../public/lib/transform.mjs`.

- [ ] **Step 3: Write the implementation**

Create `public/lib/transform.mjs`:

```js
// Builds the ELK JSON graph from a validated graph. Text measurement is
// injected so this module stays DOM-free and testable.

export const NODE_FONT = "bold 14px system-ui, sans-serif";
export const SUB_FONT = "11px system-ui, sans-serif";

export function toElk(graph, measure) {
  function nodeElk(n) {
    const w = Math.max(
      measure(n.name, NODE_FONT),
      n.type ? measure(`(${n.type})`, SUB_FONT) : 0,
    );
    return {
      id: `n:${n.id}`,
      width: Math.ceil(w) + 24,
      height: n.type ? 44 : 30,
    };
  }

  function boxElk(b) {
    return {
      id: `b:${b.name}`,
      layoutOptions: {"elk.padding": "[top=40,left=14,bottom=14,right=14]"},
      children: b.components.map(c =>
        c.startsWith("n:")
          ? nodeElk(graph.nodes.get(c.slice(2)))
          : boxElk(graph.boxesByName.get(c.slice(2)))),
    };
  }

  const children = [];
  for (const n of graph.nodes.values()) {
    if (!graph.parentOf.has(`n:${n.id}`)) children.push(nodeElk(n));
  }
  for (const b of graph.boxes) {
    if (!graph.parentOf.has(`b:${b.name}`)) children.push(boxElk(b));
  }

  return {
    id: "root",
    layoutOptions: {
      "elk.algorithm": "layered",
      "elk.direction": "RIGHT",
      "elk.hierarchyHandling": "INCLUDE_CHILDREN",
      "elk.layered.spacing.nodeNodeBetweenLayers": "50",
      "elk.spacing.nodeNode": "30",
      "elk.spacing.edgeNode": "20",
      "elk.padding": "[top=20,left=20,bottom=20,right=20]",
    },
    children,
    edges: graph.edges.map(e => ({
      id: e.id,
      sources: [`n:${e.source}`],
      targets: [`n:${e.target}`],
    })),
  };
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test test/`
Expected: all tests PASS.

- [ ] **Step 5: Commit**

```bash
git add public/lib/transform.mjs test/transform.test.mjs
git commit -m "feat: transform validated graph to ELK layered input"
```

---

### Task 4: Vendor ELK.js and prove the layout pipeline end to end

**Files:**
- Create: `public/vendor/elk.bundled.js` (downloaded, committed)
- Test: `test/layout.test.mjs`

**Interfaces:**
- Consumes: `validate` (Task 2), `toElk` (Task 3).
- Produces: the vendored `public/vendor/elk.bundled.js` exposing the `ELK` constructor (UMD global in browser, CJS export in node). Layout usage everywhere: `await new ELK().layout(elkGraph)`.

- [ ] **Step 1: Download and commit ELK**

```bash
mkdir -p public/vendor
curl -fSL -o public/vendor/elk.bundled.js https://unpkg.com/elkjs@0.9.3/lib/elk.bundled.js
ls -la public/vendor/elk.bundled.js   # expect > 1 MB
```

- [ ] **Step 2: Write the integration test (failing check first)**

Create `test/layout.test.mjs`:

```js
import {test} from "node:test";
import assert from "node:assert/strict";
import {createRequire} from "node:module";
import {validate} from "../public/lib/validate.mjs";
import {toElk} from "../public/lib/transform.mjs";

const require = createRequire(import.meta.url);
const ELK = require("../public/vendor/elk.bundled.js");

test("ELK lays out a nested boxed graph end to end", async () => {
  const g = validate({
    nodes: {a: {type: "svc"}, b: {type: "db"}, c: {}},
    edges: [
      {nodes: ["a", "b"], direction: "->"},
      {nodes: ["c", "a"], direction: "<-"},
      {nodes: ["b", "c"], direction: "<->"},
    ],
    boxes: [{name: "grp", components: ["a", "b"]}],
  });
  const layout = await new ELK().layout(toElk(g, t => t.length * 7));

  assert.ok(layout.width > 0 && layout.height > 0);

  const grp = layout.children.find(ch => ch.id === "b:grp");
  assert.ok(grp, "box present in layout");
  assert.equal(grp.children.length, 2);
  for (const child of grp.children) {
    assert.ok(child.x !== undefined && child.y !== undefined);
  }

  // every edge got routed with at least one section
  assert.equal(layout.edges.length, 3);
  for (const e of layout.edges) {
    assert.ok(e.sections && e.sections.length > 0, `edge ${e.id} has sections`);
  }
});
```

- [ ] **Step 3: Run the test**

Run: `node --test test/`
Expected: all tests PASS. If the ELK require fails or sections are missing, fix the transform (not the test) — the ELK JSON format reference is https://eclipse.dev/elk/documentation/tooldevelopers/graphdatastructure/jsonformat.html.

- [ ] **Step 4: Commit**

```bash
git add public/vendor/elk.bundled.js test/layout.test.mjs
git commit -m "feat: vendor elkjs 0.9.3 and add layout integration test"
```

---

### Task 5: Babashka server

**Files:**
- Create: `bb.edn`
- Create: `server/serve.clj`
- Test: `test/server_test.clj`

**Interfaces:**
- Consumes: nothing from other tasks (parallel-safe with Tasks 1–4).
- Produces:
  - `bb serve <file.edn>` — serves `public/` on port 8080 plus `GET /api/graph` (EDN as JSON; parse errors → `{"error": "..."}` with status 200) and `GET /api/version` (`{"mtime": <long>}`).
  - `serve/edn->json [s] -> json-string` (pure, tested).
  - `bb test` — runs Clojure tests then `node --test test/`.

- [ ] **Step 1: Write the failing test**

Create `test/server_test.clj`:

```clojure
(ns server-test
  (:require [clojure.test :refer [deftest is]]
            [cheshire.core :as json]
            [serve]))

(deftest converts-keywords-and-sets-to-plain-json
  (let [out (json/parse-string
             (serve/edn->json
              (str "{:nodes {\"a\" {:name \"A\" :type \"svc\" :role [:active :passive]}}"
                   " :edges [{:nodes [\"a\" \"a\"] :direction :<-> :name \"self\"}]"
                   " :boxes [{:name \"g\" :components #{\"a\"}}]}")))]
    (is (= "<->" (get-in out ["edges" 0 "direction"])))
    (is (= ["active" "passive"] (get-in out ["nodes" "a" "role"])))
    (is (= ["a"] (get-in out ["boxes" 0 "components"])))
    (is (= "svc" (get-in out ["nodes" "a" "type"])))))

(deftest parse-error-becomes-error-json
  (let [out (json/parse-string (serve/edn->json "{:unclosed"))]
    (is (contains? out "error"))
    (is (string? (get out "error")))))
```

Create `bb.edn`:

```clojure
{:paths ["server" "test"]
 :tasks
 {serve    {:doc "Serve an EDN graph file: bb serve examples/demo.edn"
            :requires ([serve])
            :task (apply serve/-main *command-line-args*)}
  test:clj {:doc "Run Clojure server tests"
            :requires ([clojure.test :as t] [server-test])
            :task (let [{:keys [fail error]} (t/run-tests 'server-test)]
                    (when (pos? (+ fail error)) (System/exit 1)))}
  test:js  {:doc "Run JS unit tests"
            :task (shell "node --test test/")}
  test     {:doc "Run all tests"
            :depends [test:clj test:js]}}}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bb test:clj`
Expected: FAIL — namespace `serve` not found.

- [ ] **Step 3: Write the implementation**

Create `server/serve.clj`:

```clojure
(ns serve
  "Dumb static + EDN->JSON server. All graph logic lives in the browser."
  (:require [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as srv]))

(def edn-file (atom nil))

(defn edn->json
  "Parse an EDN string into a JSON string. Keywords become their names
  (:-> becomes \"->\"), sets become arrays. Parse failures return
  {\"error\": message} instead of throwing."
  [s]
  (try
    (json/generate-string (edn/read-string s))
    (catch Exception e
      (json/generate-string {:error (ex-message e)}))))

(def mime-types
  {"html" "text/html; charset=utf-8"
   "css"  "text/css; charset=utf-8"
   "js"   "text/javascript; charset=utf-8"
   "mjs"  "text/javascript; charset=utf-8"
   "json" "application/json"
   "svg"  "image/svg+xml"})

(defn- json-response [body]
  {:status 200
   :headers {"Content-Type" "application/json"
             "Cache-Control" "no-store"}
   :body body})

(defn- static-response [uri]
  (let [path (if (= uri "/") "/index.html" uri)
        file (io/file "public" (subs path 1))]
    (if (and (.isFile file) (not (str/includes? path "..")))
      {:status 200
       :headers {"Content-Type" (get mime-types
                                     (last (str/split (.getName file) #"\."))
                                     "application/octet-stream")
                 "Cache-Control" "no-store"}
       :body file}
      {:status 404 :body "not found"})))

(defn handler [{:keys [uri]}]
  (case uri
    "/api/graph"   (json-response (edn->json (slurp @edn-file)))
    "/api/version" (json-response (json/generate-string
                                   {:mtime (.lastModified (io/file @edn-file))}))
    (static-response uri)))

(defn -main [& args]
  (let [file (first args)]
    (when (or (nil? file) (not (.isFile (io/file file))))
      (println "usage: bb serve <graph.edn>")
      (System/exit 1))
    (reset! edn-file file)
    (srv/run-server handler {:port 8080})
    (println (str "simpleviz: serving " file " at http://localhost:8080"))
    @(promise)))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bb test`
Expected: Clojure tests PASS, then all node tests PASS.

- [ ] **Step 5: Verify the endpoints manually**

```bash
echo '{:nodes {"a" {:type "svc"}}}' > /tmp/t.edn
bb serve /tmp/t.edn &
sleep 2
curl -s localhost:8080/api/graph        # expect {"nodes":{"a":{"type":"svc"}}}
curl -s localhost:8080/api/version      # expect {"mtime":<number>}
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/api/graph  # 200
kill %1
```

(`/` returns 404 until Task 6 adds `public/index.html` — that's expected here.)

- [ ] **Step 6: Commit**

```bash
git add bb.edn server/serve.clj test/server_test.clj
git commit -m "feat: babashka server with EDN->JSON api and static files"
```

---

### Task 6: Frontend — page shell, SVG renderer, details sidebar, live reload, demo

**Files:**
- Create: `public/index.html`
- Create: `public/style.css`
- Create: `public/lib/render.mjs`
- Create: `public/app.js`
- Create: `examples/demo.edn`

**Interfaces:**
- Consumes: everything — `validate`, `toElk`/`NODE_FONT`/`SUB_FONT`, `colorMap`/`NODE_TABLE`/`BOX_TABLE`/`NEUTRAL_NODE`/`NEUTRAL_BOX`, the vendored `ELK` global, the server endpoints.
- Produces:
  - `render.mjs`: `measure(text, font) -> width`, `setupPanZoom(svgEl)` (call once), `render(svgEl, layout, graph, colors, onSelect)` where `colors = {node: Map, box: Map, neutralNode, neutralBox}` and `onSelect` receives `{kind, title, subtitle, attrs}` or `null` (background click).
  - `app.js`: fetch/poll/orchestrate loop, banner, details panel.

- [ ] **Step 1: Write the page shell**

Create `public/index.html`:

```html
<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>simpleviz</title>
<link rel="stylesheet" href="/style.css">
</head>
<body>
<svg id="canvas"></svg>
<div id="banner" hidden></div>
<aside id="details" hidden></aside>
<script src="/vendor/elk.bundled.js"></script>
<script type="module" src="/app.js"></script>
</body>
</html>
```

Create `public/style.css`:

```css
* { box-sizing: border-box; }
body { margin: 0; font-family: system-ui, sans-serif; overflow: hidden; }

#canvas { position: fixed; inset: 0; width: 100vw; height: 100vh;
          background: #fafafa; cursor: grab; }
#canvas:active { cursor: grabbing; }

#banner { position: fixed; top: 0; left: 0; right: 0; z-index: 10;
          padding: 8px 14px; font-size: 13px; white-space: pre-line; }
#banner.error   { background: #fde8e8; color: #9b1c1c; border-bottom: 1px solid #f8b4b4; }
#banner.warning { background: #fdf6b2; color: #723b13; border-bottom: 1px solid #fce96a;
                  cursor: pointer; }
#banner.warning.collapsed { max-height: 2.3em; overflow: hidden; }

#details { position: fixed; top: 0; right: 0; bottom: 0; width: 320px;
           overflow: auto; background: #fff; border-left: 1px solid #ddd;
           padding: 16px; z-index: 5; box-shadow: -2px 0 8px rgba(0,0,0,.06); }
#details h2 { margin: 0 0 2px; font-size: 16px; }
#details .details-type { color: #666; font-size: 12px; margin-bottom: 12px; }
#details dt { font-weight: 600; font-size: 12px; margin-top: 10px; color: #444; }
#details dd { margin: 2px 0 0; font-size: 13px; white-space: pre-wrap;
              font-family: ui-monospace, "Cascadia Mono", monospace; }

/* SVG — fonts here must match NODE_FONT / SUB_FONT in transform.mjs */
.node-bg   { fill: #fff; stroke: #ddd; }
.node-name { font: bold 14px system-ui, sans-serif; }
.node-sub  { font: 11px system-ui, sans-serif; fill: #888; }
.box-name  { font: bold 13px system-ui, sans-serif; }
.box-sub   { font-weight: normal; font-size: 11px; fill: #888; }
.edge-line { stroke: #555; stroke-width: 1.5; }
.edge-hit  { stroke: transparent; stroke-width: 12; pointer-events: stroke; }
.edge-label { font: 11px system-ui, sans-serif; fill: #444;
              paint-order: stroke; stroke: #fafafa; stroke-width: 3; }
.selectable { cursor: pointer; }
.selected .node-bg, .selected .box-bg { stroke: #2563eb; stroke-width: 2; }
g.edge.selected .edge-line { stroke: #2563eb; stroke-width: 2.5; }
```

- [ ] **Step 2: Write the renderer**

Create `public/lib/render.mjs`:

```js
// SVG renderer + pan/zoom + selection. DOM-only module; knows nothing
// about EDN or the server.

const SVG_NS = "http://www.w3.org/2000/svg";

function el(tag, attrs = {}, text) {
  const node = document.createElementNS(SVG_NS, tag);
  for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, v);
  if (text !== undefined) node.textContent = text;
  return node;
}

const measureCtx = document.createElement("canvas").getContext("2d");
export function measure(text, font) {
  measureCtx.font = font;
  return measureCtx.measureText(text).width;
}

// Pan/zoom state survives re-renders so live reload keeps the view.
const view = {x: 0, y: 0, k: 1, initialized: false};
let suppressClick = false;

function applyView(svgEl) {
  const vp = svgEl.querySelector("#viewport");
  if (vp) vp.setAttribute("transform",
    `translate(${view.x},${view.y}) scale(${view.k})`);
}

export function setupPanZoom(svgEl) {
  svgEl.addEventListener("wheel", (e) => {
    e.preventDefault();
    const factor = e.deltaY < 0 ? 1.1 : 1 / 1.1;
    const rect = svgEl.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;
    view.x = mx - (mx - view.x) * factor;
    view.y = my - (my - view.y) * factor;
    view.k *= factor;
    applyView(svgEl);
  }, {passive: false});

  let drag = null;
  svgEl.addEventListener("pointerdown", (e) => {
    drag = {x: e.clientX, y: e.clientY, vx: view.x, vy: view.y, moved: false};
    svgEl.setPointerCapture(e.pointerId);
  });
  svgEl.addEventListener("pointermove", (e) => {
    if (!drag) return;
    const dx = e.clientX - drag.x;
    const dy = e.clientY - drag.y;
    if (Math.abs(dx) + Math.abs(dy) > 3) drag.moved = true;
    view.x = drag.vx + dx;
    view.y = drag.vy + dy;
    applyView(svgEl);
  });
  svgEl.addEventListener("pointerup", () => {
    if (drag && drag.moved) suppressClick = true;
    drag = null;
  });
}

function midpoint(pts) {
  let total = 0;
  const lens = [];
  for (let i = 1; i < pts.length; i++) {
    const len = Math.hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y);
    lens.push(len);
    total += len;
  }
  let acc = 0;
  for (let i = 0; i < lens.length; i++) {
    if (acc + lens[i] >= total / 2) {
      const t = (total / 2 - acc) / (lens[i] || 1);
      return {
        x: pts[i].x + (pts[i + 1].x - pts[i].x) * t,
        y: pts[i].y + (pts[i + 1].y - pts[i].y) * t,
      };
    }
    acc += lens[i];
  }
  return pts[0];
}

export function render(svgEl, layout, graph, colors, onSelect) {
  svgEl.textContent = "";

  const defs = el("defs");
  const marker = el("marker", {
    id: "arrow", viewBox: "0 0 10 10", refX: 9, refY: 5,
    markerWidth: 7, markerHeight: 7, orient: "auto-start-reverse",
  });
  marker.append(el("path", {d: "M 0 0 L 10 5 L 0 10 z", fill: "#555"}));
  defs.append(marker);
  svgEl.append(defs);

  const viewport = el("g", {id: "viewport"});
  const layers = {boxes: el("g"), edges: el("g"), nodes: el("g")};
  viewport.append(layers.boxes, layers.edges, layers.nodes);
  svgEl.append(viewport);

  const edgesById = new Map(graph.edges.map(e => [e.id, e]));

  function selectable(group, payload) {
    group.classList.add("selectable");
    group.addEventListener("click", (e) => {
      e.stopPropagation();
      if (suppressClick) { suppressClick = false; return; }
      svgEl.querySelectorAll(".selected")
        .forEach(n => n.classList.remove("selected"));
      group.classList.add("selected");
      onSelect(payload);
    });
  }

  function drawNode(child, x, y) {
    const node = graph.nodes.get(child.id.slice(2));
    const color = node.type ? colors.node.get(node.type) : colors.neutralNode;
    const g = el("g", {class: "node", transform: `translate(${x},${y})`});
    g.append(el("rect", {
      class: "node-bg", width: child.width, height: child.height, rx: 6,
    }));
    const cx = child.width / 2;
    g.append(el("text", {
      class: "node-name", x: cx, y: 19, "text-anchor": "middle", fill: color,
    }, node.name));
    if (node.type) {
      g.append(el("text", {
        class: "node-sub", x: cx, y: 35, "text-anchor": "middle",
      }, `(${node.type})`));
    }
    selectable(g, {kind: "node", title: node.name,
                   subtitle: node.type, attrs: node.attrs});
    layers.nodes.append(g);
  }

  function drawBox(child, x, y) {
    const box = graph.boxesByName.get(child.id.slice(2));
    const c = box.type ? colors.box.get(box.type) : colors.neutralBox;
    const g = el("g", {class: "box", transform: `translate(${x},${y})`});
    g.append(el("rect", {
      class: "box-bg", width: child.width, height: child.height, rx: 10,
      fill: c.fill, stroke: c.border,
    }));
    const label = el("text", {class: "box-name", x: 12, y: 24, fill: c.border},
                     box.name);
    if (box.type) {
      const tspan = document.createElementNS(SVG_NS, "tspan");
      tspan.setAttribute("class", "box-sub");
      tspan.textContent = ` (${box.type})`;
      label.append(tspan);
    }
    g.append(label);
    selectable(g, {kind: "box", title: box.name,
                   subtitle: box.type, attrs: box.attrs});
    layers.boxes.append(g);
  }

  function drawEdge(elkEdge) {
    const e = edgesById.get(elkEdge.id);
    const sec = (elkEdge.sections || [])[0];
    if (!e || !sec) return;
    const pts = [sec.startPoint, ...(sec.bendPoints || []), sec.endPoint];
    const d = pts.map((p, i) => `${i ? "L" : "M"} ${p.x} ${p.y}`).join(" ");
    const g = el("g", {class: "edge"});
    const path = el("path", {class: "edge-line", d, fill: "none"});
    if (e.arrows.target) path.setAttribute("marker-end", "url(#arrow)");
    if (e.arrows.source) path.setAttribute("marker-start", "url(#arrow)");
    g.append(path);
    g.append(el("path", {class: "edge-hit", d, fill: "none"}));
    const text = [e.name, e.type ? `(${e.type})` : ""].filter(Boolean).join(" ");
    if (text) {
      const mid = midpoint(pts);
      g.append(el("text", {
        class: "edge-label", x: mid.x, y: mid.y - 5, "text-anchor": "middle",
      }, text));
    }
    selectable(g, {kind: "edge",
                   title: e.name || `${e.source} → ${e.target}`,
                   subtitle: e.type, attrs: e.attrs});
    layers.edges.append(g);
  }

  (function walk(parent, ox, oy) {
    for (const child of parent.children || []) {
      const x = ox + child.x;
      const y = oy + child.y;
      if (child.id.startsWith("b:")) {
        drawBox(child, x, y);
        walk(child, x, y);
      } else {
        drawNode(child, x, y);
      }
    }
  })(layout, 0, 0);
  for (const elkEdge of layout.edges || []) drawEdge(elkEdge);

  // Background click clears selection. Property assignment (not
  // addEventListener) so repeated renders don't stack handlers.
  svgEl.onclick = () => {
    if (suppressClick) { suppressClick = false; return; }
    svgEl.querySelectorAll(".selected")
      .forEach(n => n.classList.remove("selected"));
    onSelect(null);
  };

  if (!view.initialized) {
    view.initialized = true;
    const rect = svgEl.getBoundingClientRect();
    view.k = Math.min(1.25,
      0.9 * Math.min(rect.width / (layout.width || 1),
                     rect.height / (layout.height || 1)));
    view.x = (rect.width - (layout.width || 0) * view.k) / 2;
    view.y = (rect.height - (layout.height || 0) * view.k) / 2;
  }
  applyView(svgEl);
}
```

- [ ] **Step 3: Write the app orchestration**

Create `public/app.js`:

```js
import {validate} from "./lib/validate.mjs";
import {toElk} from "./lib/transform.mjs";
import {colorMap, NODE_TABLE, BOX_TABLE, NEUTRAL_NODE, NEUTRAL_BOX}
  from "./lib/colors.mjs";
import {render, setupPanZoom, measure} from "./lib/render.mjs";

const elk = new ELK();
const svg = document.getElementById("canvas");
const banner = document.getElementById("banner");
const details = document.getElementById("details");

setupPanZoom(svg);

function showBanner(kind, lines) {
  if (!lines.length) { banner.hidden = true; return; }
  banner.hidden = false;
  banner.className = kind;
  banner.textContent = lines.join("\n");
  // warnings are collapsible by click; errors always fully visible
  banner.onclick = kind === "warning"
    ? () => banner.classList.toggle("collapsed")
    : null;
}

function showDetails(sel) {
  if (!sel) { details.hidden = true; return; }
  details.hidden = false;
  details.textContent = "";
  const h = document.createElement("h2");
  h.textContent = sel.title;
  details.append(h);
  if (sel.subtitle) {
    const sub = document.createElement("div");
    sub.className = "details-type";
    sub.textContent = `(${sel.subtitle})`;
    details.append(sub);
  }
  const dl = document.createElement("dl");
  for (const [k, v] of Object.entries(sel.attrs)) {
    const dt = document.createElement("dt");
    dt.textContent = k;
    const dd = document.createElement("dd");
    dd.textContent = typeof v === "string" ? v : JSON.stringify(v, null, 2);
    dl.append(dt, dd);
  }
  details.append(dl);
}

async function reload() {
  const raw = await (await fetch("/api/graph")).json();
  if (raw.error) {
    showBanner("error", [`EDN parse error: ${raw.error}`]);
    return; // keep the last good render on screen
  }
  const g = validate(raw);
  showBanner("warning", g.warnings);
  const colors = {
    node: colorMap([...g.nodes.values()].map(n => n.type), NODE_TABLE),
    box: colorMap(g.boxes.map(b => b.type), BOX_TABLE),
    neutralNode: NEUTRAL_NODE,
    neutralBox: NEUTRAL_BOX,
  };
  const layout = await elk.layout(toElk(g, measure));
  render(svg, layout, g, colors, showDetails);
}

let lastMtime = null;
async function tick() {
  try {
    const {mtime} = await (await fetch("/api/version")).json();
    if (mtime !== lastMtime) {
      lastMtime = mtime;
      await reload();
    }
  } catch {
    // server briefly unreachable — retry on next tick
  }
}

tick();
setInterval(tick, 1000);
```

- [ ] **Step 4: Write the demo graph**

Create `examples/demo.edn`:

```clojure
{:nodes {"web"    {:name "Web UI"   :type "frontend" :framework "htmx" :role [:active]}
         "api"    {:name "API"      :type "service"  :lang "clojure" :replicas 3}
         "auth"   {:name "Auth"     :type "service"}
         "db"     {:name "Postgres" :type "database" :version "16"}
         "cache"  {:name "Redis"    :type "cache"}
         "worker" {:name "Worker"   :type "service"}
         "queue"  {:name "Queue"    :type "queue"}
         "mail"   {:name "Mailer"   :type "external" :provider "ses"}}
 :edges [{:nodes ["web" "api"]      :direction :->  :name "REST"    :type "http" :auth "bearer"}
         {:nodes ["api" "db"]       :direction :->  :name "queries" :type "sql"}
         {:nodes ["api" "cache"]    :direction :<-> :name "session" :type "resp"}
         {:nodes ["queue" "api"]    :direction :<-  :name "publish" :type "amqp"}
         {:nodes ["worker" "queue"] :direction :->  :name "consume" :type "amqp"}
         {:nodes ["worker" "mail"]  :direction :->  :name "send"    :type "smtp"}
         {:nodes ["api" "auth"]     :direction :-   :name "trust"}]
 :boxes [{:name "backend" :type "zone" :components #{"api" "auth" "storage" "worker" "queue"}
          :owner "platform-team"}
         {:name "storage" :type "zone" :components #{"db" "cache"}}]}
```

(Exercises: all four directions, nested boxes — `storage` inside `backend` —, hidden attributes on nodes/edges/boxes, an untyped edge, and two same-type boxes.)

- [ ] **Step 5: Verify against the running server**

```bash
bb serve examples/demo.edn &
sleep 2
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/            # 200
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/app.js      # 200
curl -s -o /dev/null -w "%{http_code}\n" localhost:8080/lib/render.mjs  # 200
curl -s localhost:8080/api/graph | head -c 200                      # JSON, no "error"
kill %1
```

Then run the full suite: `bb test` — all PASS.

- [ ] **Step 6: Browser check**

Start `bb serve examples/demo.edn`, open http://localhost:8080 and verify:
1. Graph renders left-to-right; `backend` box contains `storage` box.
2. Node names colored by type (`api`, `auth`, `worker` share one color), `(type)` beneath.
3. Arrowheads: `REST` one arrow, `session` both, `trust` none, `publish` points api→queue.
4. Click a node → sidebar shows all attributes incl. `framework`/`role`; background click closes it.
5. Pan by dragging, zoom with wheel.
6. Edit `examples/demo.edn` (rename a node) — page updates within ~1 s, keeping pan/zoom.
7. Break the file (delete a closing brace) — red error banner, old graph stays; fix it — banner clears.

If this is an automated run without a browser, use available browser tooling (e.g. claude-in-chrome) or ask the user to verify; do not skip this step silently.

- [ ] **Step 7: Commit**

```bash
git add public/index.html public/style.css public/lib/render.mjs public/app.js examples/demo.edn
git commit -m "feat: SVG frontend with live reload, details panel, demo graph"
```

---

### Task 7: README and final verification

**Files:**
- Modify: `README.md`

**Interfaces:**
- Consumes: everything.
- Produces: user-facing docs.

- [ ] **Step 1: Write the README**

Replace `README.md` content with:

```markdown
# simpleviz

Minimal EDN-driven graph visualization. Describe nodes, directed edges, and
nested grouping boxes in an EDN file; view it as an auto-layouted SVG diagram
that live-reloads while you edit the file.

## Requirements

- [babashka](https://babashka.org/)
- A browser

## Usage

    bb serve examples/demo.edn

Open http://localhost:8080. Edit the file — the page updates automatically.
Click nodes, edges, or boxes for their full attributes. Drag to pan, wheel to zoom.

## Data format

    {:nodes {"api" {:name "API"          ; display name (defaults to the key)
                    :type "service"      ; free-form; colors the name, shown as (type)
                    :lang "clojure"}}    ; any other attr: details panel only
     :edges [{:nodes ["web" "api"]       ; vector: order defines left/right
              :direction :->             ; :-> | :<- | :<-> | :- (default :-)
              :name "REST"
              :type "http"}]
     :boxes [{:name "backend"
              :type "zone"               ; colors the box (separate palette)
              :components #{"api" "db"}}]} ; node and/or box names; boxes nest

Colors are stable: a type keeps its color across restarts and unrelated edits
(FNV-1a hash into a fixed 255-color table, golden-angle hues, linear probing
on collision).

Invalid references, duplicate box memberships, or containment cycles never
break rendering — the element is skipped and a warning banner explains it.
A parse error shows an error banner and keeps the last good render.

## Development

    bb test        # Clojure server tests + JS unit tests
    node --test test/   # JS only

Layout: vendored [ELK.js](https://github.com/kieler/elkjs) (layered,
left-to-right, compound boxes). No build step; frontend is plain ES modules.
```

- [ ] **Step 2: Full verification**

Run: `bb test`
Expected: all Clojure and JS tests PASS.

Run: `git status` — no uncommitted source files besides README.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: usage and data format"
```
