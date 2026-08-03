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
