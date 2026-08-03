// Fixed 255-entry color tables. Entry i uses hue i * golden angle, so
// ADJACENT indices are visually distinct — that makes linear probing on
// hash collision a safe "next best" choice.

const TABLE_SIZE = 255;
const GOLDEN_ANGLE = 137.508;

export function fnv1a(str) {
  let h = 0x811c9dc5;
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

function hue(i) {
  return ((i * GOLDEN_ANGLE) % 360).toFixed(1);
}

export const NODE_TABLE = Array.from({length: TABLE_SIZE}, (_, i) =>
  `hsl(${hue(i)} 65% 38%)`);

export const BOX_TABLE = Array.from({length: TABLE_SIZE}, (_, i) => ({
  border: `hsl(${hue(i)} 45% 55%)`,
  fill: `hsl(${hue(i)} 45% 55% / 0.1)`,
}));

export const NEUTRAL_NODE = "hsl(0 0% 40%)";
export const NEUTRAL_BOX = {border: "hsl(0 0% 65%)", fill: "hsl(0 0% 65% / 0.1)"};

export function assignIndices(types) {
  const sorted = [...new Set(types)].filter(t => t).sort();
  const taken = new Set();
  const result = new Map();
  for (const t of sorted) {
    let idx = fnv1a(t) % TABLE_SIZE;
    if (taken.size >= TABLE_SIZE) {
      result.set(t, idx); // table exhausted: reuse
      continue;
    }
    while (taken.has(idx)) idx = (idx + 1) % TABLE_SIZE;
    taken.add(idx);
    result.set(t, idx);
  }
  return result;
}

export function colorMap(types, table) {
  const m = new Map();
  for (const [t, i] of assignIndices(types)) m.set(t, table[i]);
  return m;
}
