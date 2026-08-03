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

function closeDetails() {
  svg.querySelectorAll(".selected").forEach(n => n.classList.remove("selected"));
  showDetails(null);
}

function showDetails(sel) {
  if (!sel) { details.hidden = true; return; }
  details.hidden = false;
  details.textContent = "";
  const close = document.createElement("button");
  close.className = "details-close";
  close.type = "button";
  close.setAttribute("aria-label", "Close details");
  close.textContent = "×";
  close.addEventListener("click", (e) => {
    e.stopPropagation();
    closeDetails();
  });
  details.append(close);
  const h = document.createElement("h2");
  h.textContent = sel.title;
  details.append(h);
  if (sel.subtitle) {
    const sub = document.createElement("div");
    sub.className = "details-type";
    sub.textContent = `(${sel.subtitle}) — ${sel.kind}`;
    details.append(sub);
  } else {
    const sub = document.createElement("div");
    sub.className = "details-type";
    sub.textContent = sel.kind;
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
  let mtime;
  try {
    const response = await fetch("/api/version");
    ({mtime} = await response.json());
  } catch {
    // server briefly unreachable — retry on next tick
    return;
  }
  if (mtime !== lastMtime) {
    lastMtime = mtime;
    try {
      await reload();
    } catch (err) {
      showBanner("error", [`Render error: ${err.message}`]);
      console.error("Reload failed:", err);
      lastMtime = null; // retry on next tick
    }
  }
}

tick();
setInterval(tick, 1000);
