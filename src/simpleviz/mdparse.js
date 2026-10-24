// Markdown text → a tree of plain JS arrays, which simpleviz.markdown
// turns into hiccup. A node is a string (text) or an array whose first
// element names it: an element's tag ("p", "em", "h2", "blockquote" ...)
// followed by its children, or one of the nodes the page renders itself:
//   ["link", raw-dest, label, kind, kids]  kids null for an image
//   ["autolink", url]
//   ["note", label]                        a footnote reference
//   ["fence", lang, code]                  a fenced code block
//   ["checkbox", checked]                  a task item's box
//   ["li-task", ...kids]   ["ol-start", start, ...items]
//   ["table", aligns, header, header-inline, body, body-inline, defs, chart]
//   ["solo", defs, chart]                  a ```simpleviz-chart block
// Parsing depends on the text alone — links, calculations and highlighting
// are the renderer's — so a parse can be kept and rendered again.

const PUNCT = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";

const fenceRe = /^( {0,3})(`{3,}|~{3,})(.*)$/;
export const headingRe = /^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$/;
const itemRe = /^( {0,3})([-*+]|\d{1,9}[.)])(?:([ \t]+)(.*))?$/;
const sepRe = /^ {0,3}\|?[ \t]*:?-+:?[ \t]*(?:\|[ \t]*:?-+:?[ \t]*)*\|?[ \t]*$/;
export const defRe =
  /^ {0,3}\[((?:[^\]\\]|\\.)+)\]:[ \t]*(<[^>]*>|\S+)(?:[ \t]+(?:"[^"]*"|'[^']*'|\([^)]*\)))?[ \t]*$/;
const autolinkRe = /<((?:https?|mailto):[^\s<>]+)>/y;
const hrRe = /^ {0,3}(?:(?:\*[ \t]*){3,}|(?:-[ \t]*){3,}|(?:_[ \t]*){3,})$/;
const quoteRe = /^ {0,3}> ?/;
export const setextRe = /^ {0,3}=+[ \t]*$/;
export const noteDefRe = /^ {0,3}\[\^([^\]\s]+)\]:[ \t]*(.*)$/;
const noteRefRe = /\[\^([^\]\s]+)\]/y;
const taskRe = /^\[([ xX])\][ \t]+/;
const spaceRe = new RegExp("\\s", "u");
const punctRe = new RegExp("[\\p{P}\\p{S}]", "u");

export function blank(l) { return l.trim() === ""; }

export function indent(l) { return l.length - l.trimStart().length; }

// A reference label as definitions match it: whitespace runs collapsed,
// lower case.
function labelKey(s) { return s.trim().replace(/\s+/g, " ").toLowerCase(); }

function stripAngles(d) {
  return d.startsWith("<") && d.endsWith(">") ? d.slice(1, d.length - 1) : d;
}

// A sticky regex's match at i of s, or null.
function matchAt(re, s, i) {
  re.lastIndex = i;
  return re.exec(s);
}

// ---------------------------------------------------------------- inline

function runLen(s, i, c) {
  let k = i;
  while (s.charAt(k) === c) k++;
  return k - i;
}

// Where the code span opened by `n` backticks closes, searching from
// `from`: the next run of exactly `n`, or -1.
function codeClose(s, from, n) {
  let k = s.indexOf("`", from);
  while (k >= 0) {
    const r = runLen(s, k, "`");
    if (r === n) return k;
    k = s.indexOf("`", k + r);
  }
  return -1;
}

// A code span's text: line ends as spaces, one space stripped from both
// ends when both have one (so a span can start or end with a backtick).
function codeText(s) {
  const t = s.replaceAll("\n", " ");
  return t.startsWith(" ") && t.endsWith(" ") && !blank(t) ? t.slice(1, t.length - 1) : t;
}

// The ] closing the [ at i — nested brackets, escapes and code spans
// skipped — or -1.
function bracketEnd(s, i) {
  const n = s.length;
  let k = i + 1, depth = 0;
  while (k < n) {
    const c = s.charAt(k);
    if (c === "\\") k += 2;
    else if (c === "`") {
      const r = runLen(s, k, "`"), e = codeClose(s, k + r, r);
      k = e >= 0 ? e + r : k + r;
    } else if (c === "[") { k++; depth++; }
    else if (c === "]") {
      if (depth === 0) return k;
      k++; depth--;
    } else k++;
  }
  return -1;
}

function closerOf(c) {
  return c === "\"" ? "\"" : c === "'" ? "'" : c === "(" ? ")" : null;
}

// The ( destination "title" ) after a link's text, its ( at p:
// [raw-destination, end], or null when it isn't one.
function linkTail(s, p) {
  const n = s.length;
  const skip = k => {
    while (k < n && " \t\n".indexOf(s.charAt(k)) >= 0) k++;
    return k;
  };
  let k = skip(p + 1), dest = null;
  if (s.charAt(k) === "<") {
    const e = s.indexOf(">", k);
    if (e > k && !s.slice(k, e).includes("\n")) { dest = s.slice(k + 1, e); k = e + 1; }
  } else {
    let j = k, depth = 0;
    while (dest === null) {
      const c = s.charAt(j);
      if (c === "" || c === " " || c === "\t" || c === "\n") { dest = s.slice(k, j); k = j; }
      else if (c === "\\") j = Math.min(n, j + 2);
      else if (c === "(") { j++; depth++; }
      else if (c === ")") {
        if (depth === 0) { dest = s.slice(k, j); k = j; }
        else { j++; depth--; }
      } else j++;
    }
  }
  if (dest === null) return null;
  const k2 = skip(k), closer = closerOf(s.charAt(k2));
  let k3 = k2;
  if (k2 > k && closer !== null) {
    const e = s.indexOf(closer, k2 + 1);
    if (e >= 0) k3 = skip(e + 1);
  }
  return s.charAt(k3) === ")" ? [dest, k3 + 1] : null;
}

// A run of emphasis delimiters, not yet matched: its length and whether
// it can open and close emphasis. emphasize counts its n down and notes
// its pos in place.
class Delim {
  constructor(delim, n, open, close) {
    this.delim = delim;
    this.n = n;
    this.orig = n;
    this.open = open;
    this.close = close;
    this.pos = 0;
  }
}

// The run of `c` (* _ ~), `r` long, at i of s — CommonMark's flanking
// rules, _ never inside a word.
function delimRun(s, i, c, r) {
  const ws = ch => spaceRe.test(ch), pn = ch => punctRe.test(ch);
  const before = i === 0 ? " " : s.charAt(i - 1);
  const after = i + r >= s.length ? " " : s.charAt(i + r);
  const left = !ws(after) && (!pn(after) || ws(before) || pn(before));
  const right = !ws(before) && (!pn(before) || ws(after) || pn(after));
  return new Delim(c, r,
    c === "_" ? left && (!right || pn(before)) : left,
    c === "_" ? right && (!left || pn(after)) : right);
}

// Tokens with the delimiters left over as the text they are, adjacent
// texts joined.
function asText(ts) {
  const acc = [];
  for (let t of ts) {
    if (t instanceof Delim) t = t.delim.repeat(t.n);
    const k = acc.length - 1;
    if (typeof t === "string" && k >= 0 && typeof acc[k] === "string") acc[k] += t;
    else acc.push(t);
  }
  return acc;
}

// Can delimiter run d close opener o: same character, ~~ only with ~~,
// and not a pair the rule of three forbids (one side can do both and the
// lengths add up to a multiple of 3 that isn't one on each side)?
function closes(o, d) {
  if (o.delim !== d.delim) return false;
  if (d.delim === "~") return o.n === d.n;
  return !((o.close || d.open) && (o.orig + d.orig) % 3 === 0 && !(o.orig % 3 === 0 && d.orig % 3 === 0));
}

// Take delimiter run d into `out`, the tokens so far: while it closes the
// nearest open run it may, wrap what lies between in em, strong or del
// (runs inside that never closed become text); what is left of it opens,
// or is text. `openers` holds the open runs, `bottom` per kind of run how
// far down a search already failed — so nothing is searched twice.
function emphasize(out, openers, bottom, d) {
  const k = d.delim + d.open + (d.orig % 3);
  for (;;) {
    let p = -1;
    if (d.close) {
      const lo = bottom.get(k) ?? 0;
      for (let i = openers.length - 1; i >= lo; i--) {
        if (closes(openers[i], d)) { p = i; break; }
      }
    }
    if (p < 0) {
      if (d.close) bottom.set(k, openers.length);
      if (d.open) { d.pos = out.length; out.push(d); openers.push(d); }
      else out.push(d);
      return;
    }
    const o = openers[p];
    const use = d.delim === "~" || (o.n >= 2 && d.n >= 2) ? 2 : 1;
    const node = asText(out.splice(o.pos + 1));
    node.unshift(d.delim === "~" ? "del" : use === 2 ? "strong" : "em");
    openers.length = p + 1;
    o.n -= use;
    d.n -= use;
    if (o.n === 0) { out.pop(); openers.pop(); }
    // no failed search reaches above the stack as it now is
    for (const [bk, v] of bottom) bottom.set(bk, Math.min(v, openers.length));
    out.push(node);
    if (d.n <= 0) return;
  }
}

// The link (or image, `img`) whose text's [ is at b: [node, end], or
// null when none starts there.
function linkAt(s, b, img, doc) {
  const e = bracketEnd(s, b);
  if (e < 0) return null;
  const label = s.slice(b + 1, e), nxt = s.charAt(e + 1);
  const tail = nxt === "(" ? linkTail(s, e + 1) : null;
  let raw, kind, end;
  if (tail !== null) {
    [raw, end] = tail;
    kind = img ? "image" : "inline";
  } else if (nxt === "[") {
    const e2 = s.indexOf("]", e + 2);
    if (e2 >= 0) {
      const id = s.slice(e + 2, e2);
      raw = doc.defs.get(labelKey(id === "" ? label : id));
      kind = "ref";
      end = e2 + 1;
    }
  } else {
    raw = doc.defs.get(labelKey(label));
    kind = "ref";
    end = e + 1;
  }
  if (raw == null) return null;
  return [["link", raw, label, kind, img ? null : inline(label, doc)], end];
}

// The nodes of inline text s.
export function inline(s, doc) {
  const n = s.length, out = [], openers = [], bottom = new Map();
  let i = 0, text = "", m;
  const put = x => {
    if (text !== "") out.push(text);
    out.push(x);
    text = "";
  };
  while (i < n) {
    const c = s.charAt(i);
    if (c === "\\" && s.charAt(i + 1) === "\n") {
      put(["br"]);
      i += 2;
    } else if (c === "\\" && PUNCT.indexOf(s.charAt(i + 1)) >= 0 && i + 1 < n) {
      text += s.charAt(i + 1);
      i += 2;
    } else if (c === "\n") {
      // a line end: soft, or hard after two spaces; spaces before it go
      const t = text.trimEnd();
      if (text.endsWith("  ")) {
        text = t;
        put(["br"]);
      } else {
        out.push(t + "\n");
        text = "";
      }
      i++;
    } else if (c === "*" || c === "_" || c === "~") {
      const r = runLen(s, i, c);
      if (c === "~" && r !== 2) text += s.slice(i, i + r);
      else {
        if (text !== "") out.push(text);
        text = "";
        emphasize(out, openers, bottom, delimRun(s, i, c, r));
      }
      i += r;
    } else if (c === "[" && s.charAt(i + 1) === "^" && (m = matchAt(noteRefRe, s, i)) !== null
               && doc.notes.has(labelKey(m[1]))) {
      put(["note", labelKey(m[1])]);
      i += m[0].length;
    } else if (c === "`") {
      const r = runLen(s, i, "`"), e = codeClose(s, i + r, r);
      if (e >= 0) {
        put(["code", codeText(s.slice(i + r, e))]);
        i = e + r;
      } else {
        text += s.slice(i, i + r);
        i += r;
      }
    } else if (c === "[" || (c === "!" && s.charAt(i + 1) === "[")) {
      const img = c === "!", b = img ? i + 1 : i, hit = linkAt(s, b, img, doc);
      if (hit !== null) {
        put(hit[0]);
        i = hit[1];
      } else {
        text += s.slice(i, b + 1);
        i = b + 1;
      }
    } else if (c === "<" && (m = matchAt(autolinkRe, s, i)) !== null) {
      put(["autolink", m[1]]);
      i += m[0].length;
    } else {
      text += c;
      i++;
    }
  }
  if (text !== "") out.push(text);
  return asText(out);
}

// ---------------------------------------------------------------- blocks

// The fence line l opens, or null: {indent, mark, info, lang}.
export function fenceOpen(l) {
  const m = fenceRe.exec(l);
  if (m === null) return null;
  const mark = m[2];
  if (mark.charAt(0) === "`" && m[3].includes("`")) return null;
  const info = m[3].trim();
  return { indent: m[1].length, mark, info, lang: info.split(/\s/)[0].toLowerCase() };
}

// The mark of the fence line l opens (``` or ~~~, as long as written),
// or null.
export function fenceMark(l) {
  const f = fenceOpen(l);
  return f === null ? null : f.mark;
}

export function fenceClose(l, mark) {
  const t = l.trim();
  return indent(l) < 4 && t.length >= mark.length && t === mark.charAt(0).repeat(t.length);
}

// A table row's cells, trimmed; \| is a pipe inside a cell.
export function cells(l) {
  let t = l.trim();
  if (t.startsWith("|")) t = t.slice(1);
  if (t.endsWith("|") && !t.endsWith("\\|")) t = t.slice(0, t.length - 1);
  const acc = [];
  let cur = "", k = 0;
  while (k < t.length) {
    const c = t.charAt(k);
    if (c === "\\" && t.charAt(k + 1) === "|") { cur += "|"; k += 2; }
    else if (c === "|") { acc.push(cur.trim()); cur = ""; k++; }
    else { cur += c; k++; }
  }
  acc.push(cur.trim());
  return acc;
}

export function tableStart(lines, i) {
  return i + 1 < lines.length && lines[i].includes("|") && sepRe.test(lines[i + 1])
    && cells(lines[i]).length === cells(lines[i + 1]).length;
}

// The list item line l starts, or null: {ordered, delim, start, content,
// first} — its marker, the column its content starts at and its first
// line.
function itemOf(l) {
  const m = itemRe.exec(l);
  if (m === null) return null;
  const ind = m[1].length, mark = m[2];
  let ws = m[3] ?? "", body = m[4] ?? "";
  // 5+ blanks after the marker: the content starts after one, the rest
  // is the content's own indentation
  if (ws.length > 4) { body = ws.slice(1) + body; ws = " "; }
  const empty = blank(body), start = parseInt(mark, 10);
  return { ordered: !isNaN(start), delim: mark.charAt(mark.length - 1), start,
           content: ind + mark.length + (empty ? 1 : ws.length), first: empty ? "" : body };
}

export function isItem(l) { return itemRe.test(l); }

export function isHr(l) { return hrRe.test(l); }

export function isQuote(l) { return quoteRe.test(l); }

// Does line i end the paragraph before it?
function interrupts(lines, i) {
  const l = lines[i];
  return blank(l) || headingRe.test(l) || fenceOpen(l) !== null || hrRe.test(l) || quoteRe.test(l)
    || noteDefRe.test(l) || itemRe.test(l) || tableStart(lines, i);
}

// Could line l be paragraph text, which a lazy line may continue?
function paraLine(l) {
  return l !== undefined && !blank(l) && indent(l) < 4 && !headingRe.test(l) && !hrRe.test(l)
    && fenceOpen(l) === null;
}

// Per line: is it inside a fenced code block, its fences included?
function codeLines(lines) {
  const acc = [];
  let fence = null;
  for (const l of lines) {
    if (fence !== null) {
      acc.push(true);
      if (fenceClose(l, fence.mark)) fence = null;
    } else {
      fence = fenceOpen(l);
      acc.push(fence !== null);
    }
  }
  return acc;
}

// The line after footnote definition i: its own line, then indented
// lines and lazy text lines, blank lines between indented ones.
function noteEnd(lines, i) {
  const n = lines.length;
  let j = i + 1, blankSeen = false;
  for (; j < n; j++) {
    const l = lines[j];
    if (blank(l)) blankSeen = true;
    else if (indent(l) >= 4) blankSeen = false;
    else if (!blankSeen && !interrupts(lines, j)) blankSeen = false;
    else break;
  }
  return j;
}

function dropBlankTail(ls) {
  let n = ls.length;
  while (n > 0 && blank(ls[n - 1])) n--;
  return n === ls.length ? ls : ls.slice(0, n);
}

// The items of the list starting at line i: [items, loose, end], each
// item its lines with the content's indentation removed. A blank line
// followed by more of the list makes it loose.
function listItems(lines, i) {
  const n = lines.length, it0 = itemOf(lines[i]), items = [];
  let c = it0.content, cur = [it0.first], blankSeen = false, loose = false, j = i + 1;
  for (; j < n; j++) {
    const l = lines[j], it = itemOf(l);
    if (blank(l)) {
      cur.push("");
      blankSeen = true;
    } else if (indent(l) >= c) {
      cur.push(l.slice(c));
      loose = loose || blankSeen;
      blankSeen = false;
    } else if (hrRe.test(l)) break;
    else if (it !== null) {
      if (it.ordered !== it0.ordered || it.delim !== it0.delim) break;
      items.push(dropBlankTail(cur));
      c = it.content;
      cur = [it.first];
      loose = loose || blankSeen;
      blankSeen = false;
    } else if (!blankSeen && !interrupts(lines, j) && !setextRe.test(l)) {
      // a lazy line goes on with the item's text
      cur.push(l.trim());
    } else break;
  }
  items.push(dropBlankTail(cur));
  return [items, loose, j];
}

// The list starting at line i, and the line after it. A task item —
// [ ] or [x] first — has its checkbox first in its first paragraph; a
// tight list's items hold their paragraphs' text unwrapped.
function listBlock(lines, i, doc) {
  const it0 = itemOf(lines[i]);
  const [items, loose, end] = listItems(lines, i);
  const list = !it0.ordered ? ["ul"] : it0.start === 1 ? ["ol"] : ["ol-start", it0.start];
  for (const ls of items) {
    const m = taskRe.exec(ls[0] ?? "");
    let bs;
    if (m === null) bs = blocks(ls, doc);
    else {
      const box = ["checkbox", m[1] !== " "];
      bs = blocks([ls[0].slice(m[0].length), ...ls.slice(1)], doc);
      if (bs.length > 0 && bs[0][0] === "p") bs[0] = ["p", box, ...bs[0].slice(1)];
      else bs.unshift(box);
    }
    const li = [m === null ? "li" : "li-task"];
    for (const b of bs) {
      if (!loose && b[0] === "p") for (let k = 1; k < b.length; k++) li.push(b[k]);
      else li.push(b);
    }
    list.push(li);
  }
  return [list, end];
}

// The line after the table starting at line i.
export function tableEnd(lines, i) {
  const n = lines.length;
  let j = i + 2;
  for (; j < n; j++) {
    const l = lines[j];
    if (blank(l) || headingRe.test(l) || fenceOpen(l) !== null || hrRe.test(l) || quoteRe.test(l)
        || noteDefRe.test(l) || itemRe.test(l)) break;
  }
  return j;
}

// The table's column alignments from its separator line: "left",
// "center", "right" or null.
export function alignsOf(sep) {
  return cells(sep).map(c => {
    const l = c.startsWith(":"), r = c.endsWith(":");
    return l && r ? "center" : r ? "right" : l ? "left" : null;
  });
}

// The lines of the fenced block opening at line i, unindented as far as
// its fence, and the line closing it — -1 when none does.
function fenceBody(lines, i, fence) {
  const n = lines.length;
  let close = -1;
  for (let k = i + 1; k < n; k++) if (fenceClose(lines[k], fence.mark)) { close = k; break; }
  const body = lines.slice(i + 1, close < 0 ? n : close)
    .map(ln => ln.slice(Math.min(indent(ln), fence.indent)));
  return [body, close];
}

// For the ```simpleviz-table fence opening at line i: its text and the
// line of the table right below it, blank lines between allowed; null
// when it never closes or no table follows.
function defBlock(lines, i, fence) {
  const n = lines.length, [body, close] = fenceBody(lines, i, fence);
  if (close < 0) return null;
  let t = close + 1;
  while (t < n && blank(lines[t])) t++;
  if (t >= n) return null;
  const l = lines[t];
  return indent(l) < 4 && !headingRe.test(l) && !quoteRe.test(l) && !hrRe.test(l) && tableStart(lines, t)
    ? [body.join("\n"), t] : null;
}

// The ```simpleviz-chart-table fence right below the table ending at
// line `end`, blank lines between allowed: [its text, the line after
// it], or null when there is none or it never closes.
function chartAfter(lines, end) {
  const n = lines.length;
  let j = end;
  while (j < n && blank(lines[j])) j++;
  const fence = j < n ? fenceOpen(lines[j]) : null;
  if (fence === null || fence.info !== "simpleviz-chart-table") return null;
  const [body, close] = fenceBody(lines, j, fence);
  return close < 0 ? null : [body.join("\n"), close + 1];
}

// The table starting at line i, its def block `defs` above it, and the
// chart block right below it, if any: [its node, the line after]. Its
// cells come as written and parsed: which shows is the server's reply's
// business.
function tableAndChart(lines, i, defs, doc) {
  const end = tableEnd(lines, i), after = chartAfter(lines, end);
  const aligns = alignsOf(lines[i + 1]);
  const grid = l => {
    const cs = cells(l);
    return aligns.map((_, k) => cs[k] ?? "");
  };
  const parsed = cs => cs.map(c => inline(c, doc));
  const header = grid(lines[i]), body = [];
  for (let j = i + 2; j < end; j++) body.push(grid(lines[j]));
  return [["table", aligns, header, parsed(header), body, body.map(parsed), defs,
           after === null ? null : after[0]],
          after === null ? end : after[1]];
}

// The ```simpleviz-chart block of lines `body`: its >> lines name the data
// and its fields, the rest is the chart.
function soloChart(body) {
  let k = 0;
  while (k < body.length && body[k].trim().startsWith(">>")) k++;
  return ["solo", body.slice(0, k).join("\n"), body.slice(k).join("\n")];
}

// The nodes of the block-level lines.
export function blocks(lines, doc) {
  const n = lines.length, out = [];
  let i = 0;
  while (i < n) {
    const l = lines[i];
    if (blank(l)) { i++; continue; }
    const fence = fenceOpen(l);
    const tableDef = fence !== null && fence.info === "simpleviz-table" ? defBlock(lines, i, fence) : null;
    const solo = fence !== null && fence.info === "simpleviz-chart" ? fenceBody(lines, i, fence) : null;
    let heading;
    if (tableDef !== null) {
      const [node, end] = tableAndChart(lines, tableDef[1], tableDef[0], doc);
      out.push(node);
      i = end;
    } else if (solo !== null && solo[1] >= 0) {
      out.push(soloChart(solo[0]));
      i = solo[1] + 1;
    } else if (fence !== null) {
      const [body, close] = fenceBody(lines, i, fence);
      out.push(["fence", fence.lang, body.join("\n")]);
      i = close < 0 ? n : close + 1;
    } else if ((heading = headingRe.exec(l)) !== null) {
      const h = inline(heading[2] ?? "", doc);
      h.unshift("h" + heading[1].length);
      out.push(h);
      i++;
    } else if (indent(l) >= 4) {
      let end = i;
      while (end < n && (blank(lines[end]) || indent(lines[end]) >= 4)) end++;
      const body = dropBlankTail(lines.slice(i, end).map(ln => blank(ln) ? "" : ln.slice(4)));
      out.push(["pre", ["code", body.join("\n")]]);
      i = end;
    } else if (noteDefRe.test(l)) i = noteEnd(lines, i);
    else if (defRe.test(l)) i++;
    else if (hrRe.test(l)) { out.push(["hr"]); i++; }
    else if (quoteRe.test(l)) {
      const inner = [];
      let j = i, qfence = null;
      for (; j < n; j++) {
        const ln = lines[j];
        if (quoteRe.test(ln)) {
          const q = ln.replace(quoteRe, "");
          inner.push(q);
          qfence = qfence !== null ? (fenceClose(q, qfence.mark) ? null : qfence) : fenceOpen(q);
        } else if (qfence === null && paraLine(inner[inner.length - 1])
                   && !interrupts(lines, j) && !setextRe.test(ln)) {
          // a lazy line goes on with the quote's paragraph
          inner.push(ln);
        } else break;
      }
      const q = blocks(inner, doc);
      q.unshift("blockquote");
      out.push(q);
      i = j;
    } else if (tableStart(lines, i)) {
      const [node, end] = tableAndChart(lines, i, null, doc);
      out.push(node);
      i = end;
    } else if (itemRe.test(l)) {
      const [node, end] = listBlock(lines, i, doc);
      out.push(node);
      i = end;
    } else {
      let end = i + 1;
      while (end < n && !interrupts(lines, end) && !setextRe.test(lines[end])) end++;
      const text = lines.slice(i, end).map(ln => ln.trimStart()).join("\n").trimEnd();
      const h1 = end < n && setextRe.test(lines[end]);
      const p = inline(text, doc);
      p.unshift(h1 ? "h1" : "p");
      out.push(p);
      i = h1 ? end + 1 : end;
    }
  }
  return out;
}

// The link reference definitions in lines (not in code, no footnotes):
// label key → raw destination, the first definition of a label winning.
function defsOf(lines, code) {
  const m = new Map();
  lines.forEach((l, i) => {
    if (code[i] || noteDefRe.test(l)) return;
    const r = defRe.exec(l);
    if (r === null) return;
    const k = labelKey(r[1]);
    if (!m.has(k)) m.set(k, stripAngles(r[2]));
  });
  return m;
}

// The footnote definitions in lines (not in code): label key → their
// lines, indentation of the continuation lines removed; the first
// definition of a label wins.
function notesOf(lines, code) {
  const m = new Map();
  lines.forEach((l, i) => {
    const r = code[i] ? null : noteDefRe.exec(l);
    if (r === null || m.has(labelKey(r[1]))) return;
    const rest = lines.slice(i + 1, noteEnd(lines, i)).map(x => indent(x) >= 4 ? x.slice(4) : x.trimStart());
    m.set(labelKey(r[1]), [r[2], ...rest]);
  });
  return m;
}

// Line l with tabs in its indentation as four spaces.
export function untab(l) {
  return l.replace(/^[ \t]+/, ws => ws.replaceAll("\t", "    "));
}

// The parsed text: an opaque doc for docBlocks and noteBlocks.
export function parse(text) {
  const lines = text.replaceAll("\r\n", "\n").split("\n").map(untab);
  const code = codeLines(lines);
  const doc = { defs: defsOf(lines, code), notes: notesOf(lines, code), noteBlocks: new Map(), blocks: null };
  doc.blocks = blocks(lines, doc);
  return doc;
}

export function docBlocks(doc) { return doc.blocks; }

// The nodes of footnote `label`'s text, parsed on first use: only the
// notes a text uses render.
export function noteBlocks(doc, label) {
  let bs = doc.noteBlocks.get(label);
  if (bs === undefined) {
    bs = blocks(doc.notes.get(label), doc);
    doc.noteBlocks.set(label, bs);
  }
  return bs;
}

// The "table" and "solo" nodes a render without the server's results
// meets, in its order: the blocks', then those of the footnotes the
// text uses, in the order of their numbers. Its formula cells show as
// pending, so a footnote used only in one doesn't count.
export function tables(doc) {
  const order = new Set(), out = [];
  const walk = x => {
    if (!Array.isArray(x)) return;
    switch (x[0]) {
      case "note": order.add(x[1]); return;
      case "link": if (x[4] !== null) x[4].forEach(walk); return;
      case "solo": out.push(x); return;
      case "table": {
        out.push(x);
        x[3].forEach(walk);
        const body = x[4];
        x[5].forEach((row, r) => row.forEach((c, k) => { if (!body[r][k].startsWith("=(")) walk(c); }));
        return;
      }
      default: for (let k = 1; k < x.length; k++) walk(x[k]);
    }
  };
  doc.blocks.forEach(walk);
  // a Set iterates what is added while it does
  for (const label of order) noteBlocks(doc, label).forEach(walk);
  return out;
}
