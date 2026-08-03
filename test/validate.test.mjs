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
