// Record the "what's new in 0.47" video: docs/assets/whats-new-0.47.mp4
// and .gif — charts in md: a table shown as a chart, its Table button,
// the chart following the file, a chart of a data file alone.
//   bb build && node dev/record-whats-new-0.47.mjs [out-dir]
// Needs Node >= 22, chromium (or $CHROMIUM), ffmpeg and bb on the PATH.
// Same approach as dev/record-whats-new-0.42.mjs: serve a workspace,
// drive a headless Chromium over CDP, inject a caption and a cursor dot,
// encode the screencast.
import fs from "node:fs";
import path from "node:path";
import { recorder, ROOT, W, H, sleep } from "./recorder.mjs";

const OUT = path.resolve(process.argv[2] ?? path.join(ROOT, "docs/assets"));
const CDP_PORT = 9336, PORT = 7495;
const { ws, start, waitFor, send, js, launch, click, elAt, caption, capture, encode, report, close } =
  recorder("simpleviz-047-", { hover: true });

// ---- workspace -------------------------------------------------------------

fs.copyFileSync(path.join(ROOT, "examples/sales.csv"), path.join(ws, "sales.csv"));
const doc = path.join(ws, "report.md");
const CHART = `{:kind :bar :x :a :y [:b :c :d] :rows [1 4] :title "Units per quarter"}`;
const text = chart => `# Sales report

Units sold per quarter. The chart is the table right above its block.

| quarter | tea | coffee | juice |
|---------|----:|-------:|------:|
| Q1      | 120 | 80     | 40    |
| Q2      | 140 | 95     | 35    |
| Q3      | 110 | 120    | 60    |
| Q4      | 160 | 130    | 45    |
| total   | =(sum :b) | =(sum :c) | =(sum :d) |

\`\`\`simpleviz-chart-table
${chart}
\`\`\`

## Every sale

\`\`\`simpleviz-chart
>> [the sales](sales.csv)
>> ["units" "price"]
{:kind :scatter :x :a :y [:b] :title "Price by units sold"}
\`\`\`
`;
fs.writeFileSync(doc, text(CHART));
// the file changes as an editor or an agent would change it
const rewrite = chart => fs.writeFileSync(doc, text(chart));

// caption pill bottom center, cursor dot, an element locator
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
    el(sel, text) {
      const e = [...document.querySelectorAll(sel)].find(e => !text || e.textContent.includes(text));
      if (!e) throw new Error("no element " + sel + " " + (text ?? ""));
      const r = e.getBoundingClientRect();
      return [r.left + r.width / 2, r.top + r.height / 2];
    },
  };
})()`;

// until `cond`, a page expression, holds
async function until(cond, what, ms = 10000) {
  const ok = await js(`(async () => { for (let i = 0; i < ${ms / 50}; i++) {
    if (${cond}) return true; await new Promise(r => setTimeout(r, 50)); } return false; })()`);
  if (!ok) throw new Error("timed out waiting for " + what);
}
const scrollTo = top => js(`document.getElementById("md-view").scrollTo({ top: ${top}, behavior: "smooth" })`);
const firstChart = `document.querySelector("figure.chart-table")`;

// ---- run -------------------------------------------------------------------

try {
  start("bb", ["serve", doc, "--port", String(PORT)]);
  await waitFor(`http://127.0.0.1:${PORT}/`);
  await launch(CDP_PORT, OVERLAY);
  await send("Page.navigate", { url: `http://127.0.0.1:${PORT}/` });
  await until(`document.querySelectorAll("svg.chart-svg").length === 2`, "both charts");
  await sleep(600);

  const shots = capture();

  // 1 — a table, charted
  await js(`__rec.jump(${W / 2}, ${H - 120})`);
  await send("Page.startScreencast", { format: "png", everyNthFrame: 1, maxWidth: W, maxHeight: H });
  await caption("simpleviz 0.47: charts in md");
  await sleep(2600);

  // 2 — the block behind it
  await caption("A <code>simpleviz-chart-table</code> block right below a table charts it");
  await click(await elAt(".md-mode"), 700);
  await until(`document.getElementById("md-text")`, "the text");
  await sleep(3800);
  await caption("Columns are the table's letters; <code>:rows [1 4]</code> leaves out the total");
  await sleep(3200);
  await click(await elAt(".md-mode"), 600);
  await until(`document.querySelectorAll("svg.chart-svg").length === 2`, "the charts again");
  await sleep(800);

  // 3 — the table behind the chart
  await caption("<b>Table</b> shows the numbers — the totals are formulas");
  await click(await elAt(".chart-toggle"), 700);
  await until(`${firstChart}.classList.contains("show-table")`, "the table");
  await sleep(2800);
  await caption("<b>Chart</b> goes back");
  await click(await elAt(".chart-toggle"), 500);
  await until(`!${firstChart}.classList.contains("show-table")`, "the chart");
  await sleep(1600);
  await js(`__rec.move(${W / 2}, ${H - 110}, 500)`);

  // 4 — the chart follows the file
  await caption("Change the file and the chart follows: <code>:stack true</code>");
  rewrite(CHART.replace(":rows [1 4]", ":rows [1 4] :stack true"));
  await sleep(3400);
  await caption("<code>:kind :line</code>");
  rewrite(CHART.replace(":kind :bar", ":kind :line"));
  await until(`${firstChart}.querySelector(".chart-line")`, "the line chart");
  await sleep(2800);
  await caption("A mistake says what is wrong, and the table shows");
  rewrite(CHART.replace("[:b :c :d]", "[:b :c :e]"));
  await until(`${firstChart}.querySelector(".chart.error")`, "the error");
  await sleep(3400);
  rewrite(CHART);
  await until(`${firstChart}.querySelector(".chart-mark")`, "the bars again");
  await sleep(1000);

  // 5 — a chart alone
  await caption("A <code>simpleviz-chart</code> block charts a data file, no table: here <code>sales.csv</code>");
  await js(`__rec.move(${W - 150}, ${H / 2}, 600)`);
  await scrollTo(10000);
  await sleep(4200);
  await caption("Bars, stacked or on their side, lines and dots — <code>simpleviz check</code> reports a broken chart");
  await sleep(3400);

  // 6 — outro
  await caption("simpleviz 0.47 — github.com/sstoehrm/simpleviz");
  await sleep(2400);
  await send("Page.stopScreencast");
  encode(shots, { out: OUT, name: "whats-new-0.47" });
} catch (e) {
  report(e);
} finally {
  await close();
}
