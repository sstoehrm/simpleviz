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
