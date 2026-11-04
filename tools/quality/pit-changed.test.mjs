import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import fc from 'fast-check';
import { execFileSync } from 'node:child_process';
import { changedSources, classGlobs, codeLineNumbers, codeLines, pitPath, withinChanged } from './pit-changed.mjs';

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

test('block comments nest and may lack stars; a comment opener in a string or char literal is text', () => {
  const text = [
    'package a',
    '/* block',
    '   no star',
    '*/ val x = 1',
    'val s = "/* not a comment"',
    '// c',
    '/* a /* nested */ still */',
    'val t = 2 // tail',
    '"""raw',
    '/* inside raw',
    '""" + y',
    "val c = '\"'",
    'z',
  ].join('\n');
  assert.deepEqual([...codeLineNumbers(text)], [1, 4, 5, 8, 9, 10, 11, 12, 13]);
});

test('text inside a line comment or a closed block comment is never code', () => {
  const noNewline = fc.string().map((t) => t.replace(/[\r\n]/g, ' '));
  fc.assert(fc.property(fc.array(noNewline, { maxLength: 5 }), (lines) => codeLineNumbers(lines.map((l) => `// ${l}`).join('\n')).size === 0));
  const noMarkers = fc.string().filter((t) => !t.includes('*/') && !t.includes('/*') && !t.endsWith('*') && !t.endsWith('/'));
  fc.assert(fc.property(noMarkers, (body) => codeLineNumbers(`/*${body}*/`).size === 0));
});

test('two changed sources with one PIT path are refused, not merged', () => {
  const text = source('homer.app.infra', 'class A');
  const changed = new Map([
    [`${SRC}/homer/app/infra/A.kt`, new Set([5])],
    [`${SRC}/homer/app/elsewhere/A.kt`, new Set([5])],
  ]);
  assert.throws(() => changedSources(changed, () => text), /share PIT source path/);
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

test('main mutates the changed classes through -PpitestTargetClasses and reports only their changed lines', async () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-changed-'));
  const git = (...args) => execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { cwd: dir, encoding: 'utf8' }).trim();
  git('init', '-q');
  git('commit', '-q', '--allow-empty', '-m', 'base');
  const base = git('rev-parse', 'HEAD');
  fs.mkdirSync(path.join(dir, `${SRC}/homer/app`), { recursive: true });
  fs.writeFileSync(path.join(dir, `${SRC}/homer/app/A.kt`), source('homer.app', 'class A {\n  fun f() = 1\n}'));
  // A stand-in for Gradle: records its arguments and writes the report PIT would.
  const report = `<mutations>${[6, 7].map((line) => `<mutation status='SURVIVED'><sourceFile>A.kt</sourceFile><mutatedClass>homer.app.A</mutatedClass><mutatedMethod>f</mutatedMethod><lineNumber>${line}</lineNumber><description>d</description></mutation>`).join('')}</mutations>`;
  fs.mkdirSync(path.join(dir, 'app'), { recursive: true });
  fs.writeFileSync(path.join(dir, 'app/report.xml'), report);
  fs.writeFileSync(path.join(dir, 'app/gradlew'), '#!/bin/sh\nprintf "%s\\n" "$@" > app/args.txt\nmkdir -p app/app/build/reports/pitest\ncp app/report.xml app/app/build/reports/pitest/mutations.xml\n', { mode: 0o755 });
  git('add', '-A');
  git('commit', '-q', '-m', 'change');
  const cwd = process.cwd();
  const saved = process.env.MUTATE_BASE;
  process.env.MUTATE_BASE = base;
  process.chdir(dir);
  try {
    const { main } = await import('./pit-changed.mjs');
    assert.match(main(), /^Backend mutation testing of the change \(score 0\.0%\): 2 finding\(s\)/);
    const args = fs.readFileSync(path.join(dir, 'app/args.txt'), 'utf8').trim().split('\n');
    assert.ok(args.includes(':app:pitest'));
    assert.ok(args.includes('-PpitestTargetClasses=homer.app.A,homer.app.A$*,homer.app.AKt,homer.app.AKt$*'), args.join(' '));
  } finally {
    process.chdir(cwd);
    if (saved === undefined) delete process.env.MUTATE_BASE;
    else process.env.MUTATE_BASE = saved;
  }
});

test('the Gradle property main passes is the one the build script reads', () => {
  const build = fs.readFileSync(path.join(import.meta.dirname, '..', '..', 'app/app/build.gradle.kts'), 'utf8');
  assert.match(build, /targetClasses = providers\.gradleProperty\("pitestTargetClasses"\)/);
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
