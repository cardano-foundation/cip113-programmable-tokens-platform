/**
 * Add the `.js` Node's ESM loader requires to relative import specifiers.
 *
 * The app builds under `moduleResolution: bundler`, where extensionless relative imports are
 * correct and webpack resolves them. The offline test builds compile the same sources with
 * `tsc`, which emits those specifiers verbatim, and Node's ESM loader refuses them. Rewriting
 * the EMITTED files keeps the source correct for the thing that actually ships.
 */
import { readdirSync, readFileSync, writeFileSync, statSync } from "node:fs";
import { join } from "node:path";

const root = process.argv[2];
if (!root) throw new Error("usage: fix-esm-specifiers.mjs <dir>");

let patched = 0;
const walk = (dir) => {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      walk(full);
    } else if (entry.endsWith(".js")) {
      const before = readFileSync(full, "utf8");
      // Only relative specifiers, and only those that do not already carry an extension.
      // BOTH quote styles: tsc preserves whatever the source used, and a single-quoted module
      // silently went unrewritten until a file that used them was compiled through here.
      const after = before.replace(
        /(from\s+)(["'])(\.{1,2}\/[^"']*?)\2/g,
        (m, from, q, spec) => (/\.[a-z]+$/i.test(spec) ? m : `${from}${q}${spec}.js${q}`),
      );
      if (after !== before) {
        writeFileSync(full, after);
        patched++;
      }
    }
  }
};
walk(root);
console.log(`  [esm] rewrote relative specifiers in ${patched} file(s)`);
