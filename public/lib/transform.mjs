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
