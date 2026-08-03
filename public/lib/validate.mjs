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
      if (isBox && !isNode && c === box.name) {
        warnings.push(`box "${box.name}" cannot contain itself`);
        continue;
      }
      if (isNode && isBox) {
        warnings.push(`"${c}" names both a node and a box; box "${box.name}" gets the node`);
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
