// Fold src/i18n/parts/<ns>.<lang>.json into src/i18n/<lang>.json (deep merge
// under the top-level key <ns>), then delete the part files.
// Usage: node scripts/i18n-fold.mjs
import { readdirSync, readFileSync, writeFileSync, unlinkSync, existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const dir = resolve(dirname(fileURLToPath(import.meta.url)), "../src/i18n");
const partsDir = resolve(dir, "parts");
const isTree = (v) => v && typeof v === "object" && !Array.isArray(v);
const merge = (a, b) => {
  const out = { ...a };
  for (const [k, v] of Object.entries(b)) out[k] = isTree(out[k]) && isTree(v) ? merge(out[k], v) : v;
  return out;
};

for (const lang of ["en", "rw"]) {
  const file = resolve(dir, `${lang}.json`);
  let base = JSON.parse(readFileSync(file, "utf8"));
  if (!existsSync(partsDir)) continue;
  for (const name of readdirSync(partsDir).sort()) {
    const m = /^([\w-]+)\.(en|rw)\.json$/.exec(name);
    if (!m || m[2] !== lang) continue;
    base = merge(base, { [m[1]]: JSON.parse(readFileSync(resolve(partsDir, name), "utf8")) });
    unlinkSync(resolve(partsDir, name));
    console.log(`folded ${name}`);
  }
  writeFileSync(file, JSON.stringify(base, null, 2) + "\n");
}
