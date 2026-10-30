/**
 * `tsc` copies the package's `@/*` path alias into the declarations it emits, but the alias only
 * exists in this repo's tsconfig — a host cannot resolve it, so every cross-file type in the
 * published surface silently degrades to `any` (a host gets no contextual type for a registry
 * callback parameter, for instance). `rootDir` is `src` and the declaration output dir is
 * `dist-lib`, so an alias target maps one-to-one onto `dist-lib/<path>`: rewrite it to relative.
 *
 * Run after `tsc -p tsconfig.lib.json` (see package.json `build:lib`). Fails loudly on a target
 * missing from the output, because that means the rootDir/alias mapping this relies on changed.
 */
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const distDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../dist-lib');
// Only import/export positions, so a quoted alias path inside a JSDoc comment stays as written.
// Both quote styles matter: `tsc` emits `import("@/types/x")` for an inferred property type with
// double quotes while hand-written `from '@/x'` keeps single ones, and a one-sided pattern leaves
// the inline form in place until a CI grep catches it.
const ALIAS_IMPORT = /(from\s+|import\(\s*)(['"])@\/([^'"]+)\2/g;

function* declarations(dir) {
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) yield* declarations(full);
    else if (full.endsWith('.d.ts')) yield full;
  }
}

/** An import names no extension, so the target is either the file with one added or a directory. */
function resolvable(target) {
  return existsSync(`${target}.d.ts`) || existsSync(path.join(target, 'index.d.ts'));
}

let files = 0;
let rewritten = 0;
let missing = [];

for (const file of declarations(distDir)) {
  const source = readFileSync(file, 'utf8');
  const output = source.replace(ALIAS_IMPORT, (match, prefix, quote, target) => {
    const resolved = path.join(distDir, target);
    if (!resolvable(resolved)) {
      missing.push(`${file} -> @/${target}`);
      return match;
    }
    rewritten += 1;
    const relative = path.relative(path.dirname(file), resolved).replaceAll('\\', '/');
    return `${prefix}${quote}${relative.startsWith('.') ? relative : `./${relative}`}${quote}`;
  });
  if (output !== source) {
    writeFileSync(file, output);
    files += 1;
  }
}

console.log(`fix-lib-alias: rewrote ${rewritten} alias specifiers in ${files} declaration files`);
if (missing.length > 0) {
  console.error(`fix-lib-alias: ${missing.length} alias targets missing from ${distDir}:`);
  missing.forEach((entry) => console.error(`  ${entry}`));
  process.exit(1);
}
