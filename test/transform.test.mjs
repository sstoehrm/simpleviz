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
