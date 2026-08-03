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

test("edges wholly inside a box get container-relative section coordinates", async () => {
  // With elk.hierarchyHandling=INCLUDE_CHILDREN, ELK tags edges with a
  // `container` id and gives their sections coordinates relative to
  // that container's own origin, not the root. render.mjs must offset
  // by the container box's absolute position when drawing — this test
  // documents the contract it relies on.
  const g = validate({
    nodes: {a: {}, b: {}},
    edges: [{nodes: ["a", "b"], direction: "->"}],
    boxes: [{name: "grp", components: ["a", "b"]}],
  });
  const layout = await new ELK().layout(toElk(g, t => t.length * 7));

  const grp = layout.children.find(ch => ch.id === "b:grp");
  assert.ok(grp, "box present in layout");
  const a = grp.children.find(ch => ch.id === "n:a");
  assert.ok(a, "node a present inside box");

  assert.equal(layout.edges.length, 1);
  const edge = layout.edges[0];
  // This is the contract the renderer must honor: an edge fully inside
  // a box is tagged with that box's id as `container`, and its section
  // coordinates are relative to the *box's* origin, not the root's.
  assert.equal(edge.container, "b:grp");

  // The box itself is offset from the root by ELK's root padding, so
  // its root-absolute position is non-zero -- root-relative and
  // box-relative coordinates are genuinely different here.
  assert.ok(grp.x > 0, "box has non-zero root offset (sanity check)");

  // The edge leaves node a from its right edge, so in box-relative
  // coordinates its startPoint.x lines up with a.x + a.width, not
  // with the (larger) root-absolute equivalent grp.x + a.x + a.width.
  const start = edge.sections[0].startPoint;
  assert.equal(start.x, a.x + a.width);
  assert.ok(start.x < grp.x + a.x + a.width);
});
