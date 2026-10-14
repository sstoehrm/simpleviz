// Record the README tour: docs/assets/demo.mp4 and demo.gif.
//   bb build && node dev/record-demo.mjs [out-dir]
// Needs Node >= 22, chromium (or $CHROMIUM), ffmpeg and bb on the PATH.
// Copies the examples to a temp folder, serves them, drives a headless
// Chromium through the scenes below over the DevTools protocol and
// encodes its screencast. Captions and the cursor dot are injected DOM.
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { recorder, ROOT, W, H, sleep } from "./recorder.mjs";

const OUT = path.resolve(process.argv[2] ?? path.join(ROOT, "docs/assets"));
const CDP_PORT = 9333, PORT = 7491, CMP_PORT = 7492;
const { ws, env, start, waitFor, send, on, js, launch, moveTo, click, elAt, key, caption, settled,
        capture, encode, report, close } = recorder("simpleviz-rec-");

// ---- workspace -------------------------------------------------------------

fs.mkdirSync(path.join(ws, "api"), { recursive: true });
fs.mkdirSync(path.join(ws, "views"));
fs.copyFileSync(path.join(ROOT, "examples/demo.edn"), path.join(ws, "demo.edn"));
fs.copyFileSync(path.join(ROOT, "examples/api/internals.edn"), path.join(ws, "api/internals.edn"));
fs.writeFileSync(path.join(ws, "views/deploy.edn"), `{:nodes {:ingress {:name "Ingress" :type "k8s"}
         :api-svc {:name "api-svc" :type "deployment" :replicas 3}
         :pg      {:name "postgres-0" :type "statefulset"}
         :redis   {:name "redis" :type "deployment"}}
 :edges {[:ingress :api-svc] {:direction :-> :name "443"}
         [:api-svc :pg]      {:direction :-> :name "5432"}
         [:api-svc :redis]   {:direction :-> :name "6379"}}
 :boxes {:cluster {:name "prod cluster" :type "k8s"
                   :components #{:ingress :api-svc :pg :redis}}}}
`);
const demo = path.join(ws, "demo.edn");
const edit = (file, from, to) => {
  const s = fs.readFileSync(file, "utf8");
  if (!s.includes(from)) throw new Error(`${path.basename(file)}: no ${JSON.stringify(from)}`);
  fs.writeFileSync(file, s.replace(from, to));
};
// the API node is "the same thing" as api-svc in the deploy view
edit(demo, `:ref "api/internals.edn"}`, `:ref "api/internals.edn"\n                  :pair "views/deploy.edn#api-svc"}`);
// Auth is described by a markdown doc the viewer can edit
edit(demo, `:auth   {:name "Auth"     :type "service"}`, `:auth   {:name "Auth"     :type "service" :md-ref "docs/auth.md"}`);
fs.mkdirSync(path.join(ws, "docs"));
fs.writeFileSync(path.join(ws, "docs/auth.md"),
  "# Auth\n\nIssues bearer tokens for the Web UI.\nCalled by the [API](../api/internals.edn).\n");

// The overlay: caption pill top left, cursor dot, helpers to locate scene
// items on screen. Installed on every document (the compare server is a
// second page load).
const OVERLAY = `(() => {
  if (window.__rec) return;
  const install = () => {
    const st = document.createElement("style");
    st.textContent = \`
      #rec-cap { position: fixed; left: 16px; top: 14px; z-index: 2147483647; pointer-events: none;
        background: #1f2430; color: #fff; font: 600 17px/1.25 system-ui, sans-serif;
        padding: 9px 15px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,.25);
        transition: opacity .25s; }
      #rec-cap:empty { opacity: 0; }
      #rec-cap kbd { font: 600 15px ui-monospace, monospace; background: #3b4252; border-radius: 4px; padding: 1px 6px; }
      #rec-cur { position: fixed; left: -40px; top: -40px; width: 18px; height: 18px; margin: -9px 0 0 -9px;
        border-radius: 50%; background: rgba(59,108,240,.55); border: 2px solid #fff;
        box-shadow: 0 0 0 1px rgba(59,108,240,.9); z-index: 2147483647; pointer-events: none;
        transition: transform .12s; }
      #rec-cur.down { transform: scale(.65); }\`;
    document.head.append(st);
    const cap = Object.assign(document.createElement("div"), { id: "rec-cap" });
    const cur = Object.assign(document.createElement("div"), { id: "rec-cur" });
    document.body.append(cap, cur);
    // step below the trail or the diff legend rather than cover them
    const dodge = () => {
      const c = cap.getBoundingClientRect();
      let top = 14;
      for (const e of document.querySelectorAll("#trail, #diff-legend")) {
        const r = e.getBoundingClientRect();
        if (r.width && r.left < c.right && r.right > c.left && r.top < 14 + c.height) top = Math.max(top, r.bottom + 10);
      }
      cap.style.top = top + "px";
      requestAnimationFrame(dodge);
    };
    requestAnimationFrame(dodge);
  };
  if (document.body) install(); else document.addEventListener("DOMContentLoaded", install);
  let cx = -40, cy = -40;
  window.__rec = {
    caption(html) { document.getElementById("rec-cap").innerHTML = html; },
    jump(x, y) { cx = x; cy = y; const c = document.getElementById("rec-cur"); c.style.left = x + "px"; c.style.top = y + "px"; },
    move(x, y, ms) {
      const x0 = cx, y0 = cy, t0 = performance.now();
      return new Promise(done => {
        const step = t => {
          const u = Math.min(1, (t - t0) / ms), e = u < .5 ? 2*u*u : 1 - (-2*u + 2) ** 2 / 2;
          this.jump(x0 + (x - x0) * e, y0 + (y - y0) * e);
          u < 1 ? requestAnimationFrame(step) : done([x, y]);
        };
        requestAnimationFrame(step);
      });
    },
    press(down) { document.getElementById("rec-cur").classList.toggle("down", down); },
    // screen point of a scene item: "n:api", "b:storage" (title bar), or an
    // edge by name
    async at(id) {
      const app = await import("/js/simpleviz/app.mjs"), cv = await import("/js/simpleviz/canvas.mjs");
      const st = app.state.val;
      const it = (st.scene?.items ?? []).find(i => i.id === id || (i.kind === "edge" && i.name === id));
      if (!it) throw new Error("no scene item " + id + " in " + (st.scene?.items ?? []).map(i => i.id).join(" "));
      const v = cv.view, r = document.getElementById("canvas-wrap").getBoundingClientRect();
      let gx, gy;
      if (it.kind === "edge") { const p = it.points, a = p[Math.floor((p.length - 1) / 2)], b = p[Math.floor((p.length - 1) / 2) + 1]; gx = (a.x + b.x) / 2; gy = (a.y + b.y) / 2; }
      else if (it.kind === "box") { gx = it.x + 24; gy = it.y + it["title-h"] / 2; }
      else { gx = it.x + it.w / 2; gy = it.y + it.h / 2; }
      return [r.left + v.x + gx * v.k, r.top + v.y + gy * v.k];
    },
    // center of the first element matching a CSS selector (and text)
    el(sel, text) {
      const e = [...document.querySelectorAll(sel)].find(e => !text || e.textContent.includes(text));
      if (!e) throw new Error("no element " + sel + " " + (text ?? ""));
      const r = e.getBoundingClientRect();
      return [r.left + r.width / 2, r.top + r.height / 2];
    },
  };
})()`;

// wait until the page shows the graph file whose path ends with name
const shows = name => js(`(async () => {
  const app = await import("/js/simpleviz/app.mjs");
  for (let i = 0; i < 100; i++) {
    const st = app.state.val;
    if (String(st.graph?.path ?? "").endsWith(${JSON.stringify(name)}) && st.scene && !st.layouting) return true;
    await new Promise(r => setTimeout(r, 50));
  }
  throw new Error("never showed ${name}: " + app.state.val.graph?.path);
})()`);
async function chord(...keys) { for (const [i, k] of keys.entries()) { if (i) await sleep(260); await key(k); } }
async function type(text) { for (const ch of text) { await send("Input.insertText", { text: ch }); await sleep(70); } }
async function navigate(url) {
  await send("Page.navigate", { url });
  await sleep(600);
  await settled();
  await sleep(300);
}
async function pick(select, name) {
  await js(`(() => { const s = document.getElementById(${JSON.stringify(select)});
    s.value = ${JSON.stringify(name)}; s.dispatchEvent(new Event("change", { bubbles: true })); })()`);
}
const refit = () => js(`(async () => { const app = await import("/js/simpleviz/app.mjs"), cv = await import("/js/simpleviz/canvas.mjs");
  cv.refit_next_BANG_(); cv.fit_view_once_BANG_(app.state.val.scene); cv.request_paint_BANG_(); })()`);
const theme = name => pick("theme-select", name);

// ---- run -------------------------------------------------------------------

try {
  start("bb", ["serve", demo, "--port", String(PORT)]);
  await waitFor(`http://127.0.0.1:${PORT}/`);
  await launch(CDP_PORT, OVERLAY);

  await navigate(`http://127.0.0.1:${PORT}/`);
  const size = await js("[innerWidth, innerHeight]");
  if (size[0] !== W || size[1] !== H) throw new Error(`viewport is ${size}, wanted ${W}x${H}`);

  // screencast: frames come only on change; keep each with its timestamp
  const shots = capture();

  // 1 — title
  await js(`__rec.jump(${W / 2}, ${H / 2 + 40})`);
  await send("Page.startScreencast", { format: "png", everyNthFrame: 1, maxWidth: W, maxHeight: H });
  await caption("simpleviz — an EDN file becomes an auto-laid-out diagram");
  await sleep(2200);

  // 2 — live reload
  await caption("Edit the file: the page live-reloads");
  await sleep(500);
  edit(demo, `:logs   {:name "Log Sink"}}`,
    `:logs   {:name "Log Sink"}\n         :search {:name "Search"   :type "service"}}`);
  edit(demo, `[:worker :logs]  {:direction :->  :name "write"}`,
    `[:worker :logs]  {:direction :->  :name "write"}\n         [:api :search]   {:direction :->  :name "index"   :type "http"}`);
  edit(demo, `#{:api :auth :storage :worker :queue}`, `#{:api :auth :storage :worker :queue :search}`);
  await sleep(2200);

  // 3 — inspect
  await caption("Click anything to inspect its attributes");
  await click("n:api");
  await sleep(1700);

  // 4 — edit in place
  await caption("Edit in place: <kbd>n n n</kbd> adds a node, written back to the file");
  await click("b:storage");
  await sleep(500);
  await chord("n", "n", "n");
  await sleep(400);
  await type("S3::storage");
  await sleep(300);
  await key("Enter");
  await sleep(1800);
  await key("Escape");

  // 4b — the doc behind an :md-ref, edited as plain text
  await caption("Edit a node's markdown doc (<code>:md-ref</code>): <kbd>f m</kbd>, <kbd>Ctrl S</kbd> saves, » follows a link");
  await click("n:auth");
  await sleep(500);
  await chord("f", "m");
  await sleep(700);
  await js(`(e => { e.focus(); e.setSelectionRange(e.value.length, e.value.length); })(document.getElementById("md-text"))`);
  await type("Tokens expire after 15 minutes.\n");
  await sleep(500);
  await key("s", { modifiers: 2, text: undefined });
  await sleep(900);
  await moveTo(await elAt("#md-gutter .md-mark"), 450);
  await sleep(900);
  await click(await elAt("#md-panel .md-btn", "×"), 450);
  await sleep(400);
  await key("Escape");

  // 5 — state marks
  await caption("<code>:state</code> marks progress: new, in progress, blocked, done");
  edit(demo, `:name "Search"   :type "service"`, `:name "Search"   :type "service" :state :new`);
  edit(demo, `:lang "clojure" :replicas 3`, `:lang "clojure" :replicas 3 :state :in-progress`);
  edit(demo, `:type "database" :version "16"`, `:type "database" :version "16" :state :blocked`);
  edit(demo, `:framework "htmx"`, `:framework "htmx" :state :done`);
  await sleep(1000);
  await moveTo("n:search", 500);
  await moveTo("n:api", 400);
  await moveTo("n:db", 400);
  await moveTo("n:web", 500);
  await sleep(500);

  // 5b — text inside nodes, databases as cylinders: close the inspector
  // and fit the grown graph first
  await click(await elAt("#details-close"), 400);
  await refit();
  await caption("<code>:text</code> shows inside a node; a <code>database</code> is a cylinder");
  edit(demo, `:name "Auth"     :type "service"`, `:name "Auth"     :type "service" :text "Issues bearer tokens"`);
  edit(demo, `:version "16"`, `:version "16" :text ["Orders" "Users"]`);
  await sleep(1000);
  await moveTo("n:auth", 500);
  await sleep(500);
  await moveTo("n:db", 500);
  await sleep(1200);

  // 6 — themes
  await caption("Pick one of 14 themes — or set <code>:theme</code> in the file");
  await click(await elAt("#theme-select"));
  for (const t of ["nord", "dracula", "tabak-dark", "paper", "blueprint"]) { await theme(t); await sleep(900); }
  await theme("");
  await sleep(300);

  // 7 — pairs
  await caption("Pairs link the same thing across graphs: <kbd>f p</kbd>");
  await click("n:api");
  await sleep(900);
  await chord("f", "p");
  await shows("views/deploy.edn");
  await sleep(2000);

  // 8 — refs
  await caption("Follow a <code>:ref</code> into a sub-graph: <kbd>f r</kbd>");
  await click(await elAt(".trail-crumb"));
  await shows("demo.edn");
  await sleep(300);
  await click("n:api");
  await sleep(600);
  await chord("f", "r");
  await shows("api/internals.edn");
  await sleep(2000);

  // 9 — fork, edit, compare
  await send("Page.stopScreencast");
  // the last frame before the cut holds until the stop, not until the
  // first frame of the next screencast
  const cut = shots.length, cutHold = (Date.now() - shots.at(-1).at) / 1000;
  execFileSync("bb", ["fork", demo, "next"], { cwd: ROOT, env, stdio: "ignore" });
  const next = path.join(ws, "demo-next.edn");
  edit(next, `:mail   {:name "Mailer"   :type "external" :provider "ses"}\n`, "");
  edit(next, `[:worker :mail]  {:direction :->  :name "send"    :type "smtp"}\n`, "");
  edit(next, `:logs   {:name "Log Sink"}`, `:logs   {:name "Log Sink"}\n         :metrics {:name "Metrics" :type "service"}`);
  edit(next, `[:worker :logs]  {:direction :->  :name "write"}`,
    `[:worker :logs]  {:direction :->  :name "write"}\n         [:api :metrics]  {:direction :->  :name "emit"    :type "http"}`);
  edit(next, `:version "16"`, `:version "17"`);
  edit(next, `:auth "bearer"`, `:auth "mtls"`);
  edit(next, `:storage :worker :queue :search}`, `:storage :worker :queue :search :metrics}`);
  start("bb", ["serve", demo, "next", "--port", String(CMP_PORT)]);
  await waitFor(`http://127.0.0.1:${CMP_PORT}/`);
  await navigate(`http://127.0.0.1:${CMP_PORT}/`);
  await js(`__rec.jump(${W / 2}, ${H / 2 + 40})`);
  await send("Page.startScreencast", { format: "png", everyNthFrame: 1, maxWidth: W, maxHeight: H });
  await caption("Fork, edit, compare: <code>simpleviz demo.edn next</code>");
  await sleep(1800);
  await caption("The legend steps through every change");
  for (const row of ["added", "modified", "removed"]) { await click(await elAt("#diff-legend *", row), 450); await sleep(900); }

  // 10 — export: close the inspector and fit the whole graph again first
  await click(await elAt("#details-close"), 400);
  await refit();
  await caption("Export a PNG or an SVG, the source embedded");
  await click(await elAt("#export-btn"));
  await sleep(1800);
  await key("Escape");

  // 11 — outro
  await caption("github.com/sstoehrm/simpleviz");
  await js(`__rec.move(${W / 2}, ${H + 40}, 600)`);
  await sleep(1800);
  await send("Page.stopScreencast");
  encode(shots, { out: OUT, name: "demo", cut, cutHold });
} catch (e) {
  report(e);
} finally {
  await close();
}
