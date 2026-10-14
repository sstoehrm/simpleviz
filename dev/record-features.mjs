// Record the "what's new" video: docs/assets/whats-new.mp4 and .gif —
// md pages with GitHub markdown, Present, >> links.
//   bb build && node dev/record-features.mjs [out-dir]
// Needs Node >= 22, chromium (or $CHROMIUM), ffmpeg and bb on the PATH.
// Same approach as dev/record-demo.mjs: serve a workspace, drive a headless
// Chromium over CDP, inject a caption and a cursor dot, encode the
// screencast.
import fs from "node:fs";
import path from "node:path";
import { recorder, ROOT, W, H, sleep } from "./recorder.mjs";

const OUT = path.resolve(process.argv[2] ?? path.join(ROOT, "docs/assets"));
const CDP_PORT = 9334, PORT = 7493;
const { ws, start, waitFor, send, on, js, launch, moveTo, click, elAt, key, caption, settled,
        capture, encode, report, close } = recorder("simpleviz-feat-", { hover: true });

// ---- workspace -------------------------------------------------------------

fs.mkdirSync(path.join(ws, "api"), { recursive: true });
fs.copyFileSync(path.join(ROOT, "examples/demo.edn"), path.join(ws, "demo.edn"));
fs.copyFileSync(path.join(ROOT, "examples/api/internals.edn"), path.join(ws, "api/internals.edn"));
fs.copyFileSync(path.join(ROOT, "plugins/simpleviz/assets/icon.png"), path.join(ws, "logo.png"));
fs.writeFileSync(path.join(ws, "api/notes.md"), `# API notes

The API is the **only** way in: the Web UI and every worker go through it.

| Route | Does |
| --- | --- |
| \`/orders\` | create and list orders |
| \`/auth\` | hand out tokens |

> Every request carries a bearer token.
`);
const deck = path.join(ws, "talk.md");
fs.writeFileSync(deck, `---

# The order platform

## where a click goes

Platform team · October 2026

---

# Today

- [x] Where we are
- [x] **The system**, live
- [ ] What comes next[^q]

> Questions any time — *really*.

[^q]: Next quarter's plan, not settled yet.

---

>> [the system](demo.edn)

# The system

Every request passes the **API**.

- Hover a node for its attributes
- Click it to inspect, read-only
- Collapse a box with its −

---

>> [notes](api/notes.md)

# The API

Its notes, rendered beside the slide.

---

>> ![logo](logo.png)

# A picture

Any \`.png\`, \`.jpg\`, \`.gif\`, \`.svg\` or \`.webp\`.

---

# Thanks

~~Slides in another tool~~ — one md file.
`);

// caption pill bottom center (slides own the top), cursor dot, locators
const OVERLAY = `(() => {
  if (window.__rec) return;
  const install = () => {
    const st = document.createElement("style");
    st.textContent = \`
      #rec-cap { position: fixed; left: 50%; bottom: 22px; transform: translateX(-50%); z-index: 2147483647;
        pointer-events: none; max-width: 92vw; text-align: center;
        background: #1f2430; color: #fff; font: 600 17px/1.3 system-ui, sans-serif;
        padding: 9px 16px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,.25); transition: opacity .25s; }
      #rec-cap:empty { opacity: 0; }
      #rec-cap kbd, #rec-cap code { font: 600 15px ui-monospace, monospace; background: #3b4252; border-radius: 4px; padding: 1px 6px; }
      #rec-cur { position: fixed; left: -40px; top: -40px; width: 18px; height: 18px; margin: -9px 0 0 -9px;
        border-radius: 50%; background: rgba(59,108,240,.55); border: 2px solid #fff;
        box-shadow: 0 0 0 1px rgba(59,108,240,.9); z-index: 2147483647; pointer-events: none; transition: transform .12s; }
      #rec-cur.down { transform: scale(.65); }\`;
    document.head.append(st);
    document.body.append(Object.assign(document.createElement("div"), { id: "rec-cap" }),
                         Object.assign(document.createElement("div"), { id: "rec-cur" }));
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
    async at(id) {
      const app = await import("/js/simpleviz/app.mjs"), cv = await import("/js/simpleviz/canvas.mjs");
      const st = app.state.val;
      const it = (st.scene?.items ?? []).find(i => i.id === id);
      if (!it) throw new Error("no scene item " + id);
      const v = cv.view, r = document.getElementById("canvas-wrap").getBoundingClientRect();
      const gx = it.kind === "box" ? it.x + 24 : it.x + it.w / 2;
      const gy = it.kind === "box" ? it.y + it["title-h"] / 2 : it.y + it.h / 2;
      return [r.left + v.x + gx * v.k, r.top + v.y + gy * v.k];
    },
    el(sel, text) {
      const e = [...document.querySelectorAll(sel)].find(e => !text || e.textContent.includes(text));
      if (!e) throw new Error("no element " + sel + " " + (text ?? ""));
      const r = e.getBoundingClientRect();
      return [r.left + r.width / 2, r.top + r.height / 2];
    },
  };
})()`;

// wait until the presented slide's side has landed: its graph laid out,
// or its md/picture shown
const slideReady = () => js(`(async () => {
  const app = await import("/js/simpleviz/app.mjs");
  for (let i = 0; i < 120; i++) {
    const st = app.state.val, p = st.present;
    const side = p && p.side;
    const img = document.querySelector("#present-side img");
    const ok = p && (p.dest == null
      || (st.side && st.scene && !st.layouting)
      || (side && side.kind === "md" && side.text != null)
      || (side && side.kind === "image" && img && img.complete));
    if (ok) return true;
    await new Promise(r => setTimeout(r, 50));
  }
  return false;
})()`);
async function next() { await key("ArrowRight"); await sleep(250); await slideReady(); }
async function scrollView(sel, dy, ms) {
  await js(`(async () => { const e = document.querySelector(${JSON.stringify(sel)}), y0 = e.scrollTop, t0 = performance.now();
    await new Promise(done => { const step = t => { const u = Math.min(1, (t - t0) / ${ms});
      e.scrollTop = y0 + ${dy} * (u < .5 ? 2*u*u : 1 - (-2*u + 2) ** 2 / 2); u < 1 ? requestAnimationFrame(step) : done(); };
      requestAnimationFrame(step); }); })()`);
}

// ---- run -------------------------------------------------------------------

try {
  start("bb", ["serve", deck, "--port", String(PORT)]);
  await waitFor(`http://127.0.0.1:${PORT}/`);
  await launch(CDP_PORT, OVERLAY);
  await send("Page.navigate", { url: `http://127.0.0.1:${PORT}/` });
  await js(`(async () => { for (let i = 0; i < 100 && !document.getElementById("md-view"); i++) await new Promise(r => setTimeout(r, 50)); })()`);
  await sleep(500);

  const shots = capture();

  // 1 — the md page, rendered
  await js(`__rec.jump(${W / 2}, ${H / 2 + 60})`);
  await send("Page.startScreencast", { format: "png", everyNthFrame: 1, maxWidth: W, maxHeight: H });
  await caption("simpleviz 0.40: an md page renders GitHub markdown");
  await sleep(2000);
  await caption("Task lists, <b>bold</b> and <i>italic</i>, quotes, tables, footnotes, pictures");
  await scrollView("#md-view", 330, 2200);
  await sleep(1400);
  await scrollView("#md-view", 520, 2200);
  await sleep(1400);
  await scrollView("#md-view", -2000, 900);

  // 2 — the >> lines, in Edit
  await caption("A slide's first line <code>&gt;&gt; [text](file)</code> links what shows beside it");
  await click(await elAt(".md-mode"), 600);
  await sleep(600);
  await js(`(ta => { const i = ta.value.indexOf(">> [the system]"); ta.setSelectionRange(i, ta.value.indexOf("\\n", i)); ta.blur();
    const lh = parseFloat(getComputedStyle(ta).lineHeight); ta.scrollTop = Math.max(0, ta.value.slice(0, i).split("\\n").length * lh - 120); })(document.getElementById("md-text"))`);
  await sleep(500);
  await moveTo(await elAt(".md-mark"), 700);
  await sleep(2300);
  await click(await elAt(".md-mode"), 500);
  await sleep(400);

  // 3 — Present
  await caption("<b>Present</b> shows the page as slides, split at <code>---</code>");
  await click(await elAt(".md-present"), 600);
  await slideReady();
  await js(`__rec.move(${W / 2}, ${H + 40}, 500)`);
  await sleep(2300);
  await caption("<kbd>→</kbd> or <kbd>space</kbd> steps through the slides");
  await next();
  await sleep(2600);

  // 4 — a graph beside the slide
  await caption("<code>&gt;&gt;</code> a graph: it sits beside the slide, ready to explore");
  await next();
  await sleep(1500);
  await caption("Hover a node for its attributes");
  await moveTo("n:api", 700);
  await sleep(1700);
  await moveTo("n:db", 600);
  await sleep(1500);
  await caption("Click it to inspect — read-only while presenting");
  await click("n:api", 500);
  await sleep(2300);
  await key("Escape");
  await sleep(300);
  await caption("Collapse and expand boxes as usual");
  await click(await js(`(async () => { const [x, y] = await __rec.at("b:storage");
    const app = await import("/js/simpleviz/app.mjs"), cv = await import("/js/simpleviz/canvas.mjs");
    const it = app.state.val.scene.items.find(i => i.id === "b:storage"), v = cv.view,
          r = document.getElementById("canvas-wrap").getBoundingClientRect();
    return [r.left + v.x + (it.x + it.w - 13) * v.k, r.top + v.y + (it.y + 13) * v.k]; })()`), 600);
  await sleep(1800);
  await js(`__rec.move(${W * 0.3}, ${H + 40}, 500)`);

  // 5 — an md file beside the slide
  await caption("<code>&gt;&gt;</code> an md file: rendered beside the slide");
  await next();
  await sleep(2800);

  // 6 — a picture
  await caption("<code>&gt;&gt; ![alt](pic.png)</code>: a picture");
  await next();
  await sleep(2600);

  // 7 — the end, leave
  await next();
  await caption("<kbd>Esc</kbd> leaves; <code>?slide=4</code> in the URL survives a reload");
  await sleep(2400);
  await key("Escape");
  await sleep(900);

  // 8 — outro
  await caption("Start one: <code>simpleviz init talk.md -t presentation</code>");
  await sleep(2600);
  await caption("github.com/sstoehrm/simpleviz");
  await sleep(1800);
  await send("Page.stopScreencast");
  encode(shots, { out: OUT, name: "whats-new" });
} catch (e) {
  report(e);
} finally {
  await close();
}
