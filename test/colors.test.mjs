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
