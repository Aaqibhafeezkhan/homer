import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import fc from 'fast-check';
import { changedSources, classGlobs, codeLines, pitPath, withinChanged } from './pit-changed.mjs';

const SRC = 'app/app/src/main/kotlin';
const source = (pkg, body) => `package ${pkg}\n\nimport x.Y\n\n${body}\n`;

test('a source maps to its file class and each top-level declaration, nested classes included', () => {
  const text = source('homer.app.infra', 'class PathSafety {\n  class Inner\n}\nobject Limits\nfun helper(a: Int) = a\n');
  assert.deepEqual(classGlobs(`${SRC}/homer/app/infra/PathSafety.kt`, text), [
    'homer.app.infra.PathSafetyKt',
    'homer.app.infra.PathSafetyKt$*',
    'homer.app.infra.PathSafety',
    'homer.app.infra.PathSafety$*',
    'homer.app.infra.Limits',
    'homer.app.infra.Limits$*',
    'homer.app.infra.helper',
    'homer.app.infra.helper$*',
  ]);
});

test('a source without a package has no globs and no PIT path', () => {
  assert.deepEqual(classGlobs(`${SRC}/Main.kt`, 'fun main() {}\n'), []);
  assert.equal(pitPath(`${SRC}/Main.kt`, 'fun main() {}\n'), null);
});

test('the PIT path follows the package, not the directory', () => {
  assert.equal(pitPath(`${SRC}/homer/app/elsewhere/A.kt`, source('homer.app.infra', 'class A')), `${SRC}/homer/app/infra/A.kt`);
});

test('no glob holds a character that could widen it or split the Gradle property', () => {
  const identifier = fc.stringMatching(/^[A-Za-z_]\w{0,10}$/);
  const text = fc.tuple(fc.array(identifier, { minLength: 1, maxLength: 3 }), fc.array(identifier, { maxLength: 4 }), fc.string());
  fc.assert(
    fc.property(text, fc.string(), ([pkg, names, noise], fileName) => {
      const body = `${names.map((n) => `class ${n}`).join('\n')}\n${noise}`;
      return classGlobs(`${SRC}/${fileName}.kt`, source(pkg.join('.'), body)).every((g) => /^[\w.]+(\$\*)?$/.test(g) && !g.includes(','));
    }),
  );
});

test('only changed main sources count, test sources, scripts and unreadable files do not', () => {
  const files = {
    [`${SRC}/homer/app/A.kt`]: source('homer.app', 'class A'),
    'app/app/src/test/kotlin/homer/app/ATest.kt': source('homer.app', 'class ATest'),
    'app/app/build.gradle.kts': 'plugins {}',
    [`${SRC}/homer/app/Link.kt`]: null,
  };
  const changed = new Map([...Object.keys(files).map((f) => [f, new Set([5])]), [`${SRC}/homer/app/Deleted.kt`, new Set()]]);
  const { globs, lines } = changedSources(changed, (f) => files[f] ?? null);
  assert.deepEqual(globs, ['homer.app.A', 'homer.app.A$*', 'homer.app.AKt', 'homer.app.AKt$*']);
  assert.deepEqual([...lines.keys()], [`${SRC}/homer/app/A.kt`]);
});

test('comment, blank, package and import lines are no code lines', () => {
  const text = ['package homer.app', '', 'import x.Y', '/**', ' * Doc.', ' */', '// note', 'class A {', '    fun f() = 1 * 2', '}'].join('\n');
  assert.deepEqual([...codeLines(text, new Set([1, 2, 3, 4, 5, 6, 7, 8, 9, 11]))], [8, 9]);
});

test('a source whose changed lines are all comments is left out', () => {
  const text = source('homer.app', '// changed comment\nclass A');
  assert.deepEqual(changedSources(new Map([[`${SRC}/homer/app/A.kt`, new Set([5])]]), () => text), { globs: [], lines: new Map() });
});

test('only mutants on changed lines remain, with their repository path', () => {
  const lines = new Map([[`${SRC}/homer/app/infra/A.kt`, { path: `${SRC}/homer/app/elsewhere/A.kt`, lines: new Set([3, 4]) }]]);
  const m = (p, line) => ({ status: 'SURVIVED', path: p, line, method: 'f', description: 'd' });
  assert.deepEqual(withinChanged([m(`${SRC}/homer/app/infra/A.kt`, 3), m(`${SRC}/homer/app/infra/A.kt`, 9), m(`${SRC}/homer/app/B.kt`, 3)], lines), [
    m(`${SRC}/homer/app/elsewhere/A.kt`, 3),
  ]);
});

test('without a base the check reports itself skipped and runs no Gradle', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-changed-'));
  const cwd = process.cwd();
  const saved = process.env.MUTATE_BASE;
  delete process.env.MUTATE_BASE;
  process.chdir(dir);
  try {
    const { main } = await import('./pit-changed.mjs');
    assert.match(main(), /skipped: no MUTATE_BASE/);
    assert.equal(fs.readFileSync(path.join(dir, 'app/app/build/reports/pitest/mutation-backend-summary.txt'), 'utf8'), 'findings=0\nskipped=no MUTATE_BASE\n');
  } finally {
    process.chdir(cwd);
    if (saved !== undefined) process.env.MUTATE_BASE = saved;
  }
});
