import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import fc from 'fast-check';
import { decodeXml, isCompilerCallMutant, main, parseMutations, pitFindings, pitStats } from './pit.mjs';

const mutation = (status, { file = 'A.kt', cls = 'homer.app.config.A', method = 'run', line = 7, description = 'negated conditional' } = {}) =>
  `<mutation detected='${status === 'KILLED'}' status='${status}' numberOfTestsRun='1'><sourceFile>${file}</sourceFile>` +
  `<mutatedClass>${cls}</mutatedClass><mutatedMethod>${method}</mutatedMethod><methodDescription>()V</methodDescription>` +
  `<lineNumber>${line}</lineNumber><mutator>m</mutator><indexes><index>1</index></indexes><blocks><block>0</block></blocks>` +
  `<killingTest/><description>${description}</description></mutation>`;

const report = (...mutations) => `<?xml version="1.0" encoding="UTF-8"?>\n<mutations partial="true">\n${mutations.join('\n')}\n</mutations>\n`;

test('each mutation maps its class package and source file to a repository path', () => {
  const [m] = parseMutations(report(mutation('SURVIVED', { file: 'LogLevelOverrides.kt', cls: 'homer.app.config.LogLevelOverridesKt', line: 28 })));
  assert.deepEqual(m, {
    status: 'SURVIVED',
    path: 'app/app/src/main/kotlin/homer/app/config/LogLevelOverrides.kt',
    line: 28,
    method: 'run',
    description: 'negated conditional',
  });
});

test('a class in the default package and a nested class keep a valid path', () => {
  const [top, nested] = parseMutations(report(mutation('KILLED', { file: 'Main.kt', cls: 'MainKt' }), mutation('KILLED', { cls: 'homer.app.A$Inner' })));
  assert.equal(top.path, 'app/app/src/main/kotlin/Main.kt');
  assert.equal(nested.path, 'app/app/src/main/kotlin/homer/app/A.kt');
});

test('descriptions are decoded from XML', () => {
  const [m] = parseMutations(report(mutation('SURVIVED', { description: 'replaced return value with &quot;&quot; for a&lt;b&gt; &amp; c&#x2192;d' })));
  assert.equal(m.description, 'replaced return value with "" for a<b> & c→d');
});

test('decodeXml resolves decimal character references', () => {
  assert.equal(decodeXml('a&#8230;b&#60;'), 'a…b<');
});

test('a double-quoted status is read, a missing one is UNKNOWN', () => {
  const doubleQuoted = mutation('SURVIVED').replace("status='SURVIVED'", 'status="SURVIVED"');
  const noStatus = mutation('KILLED').replace("status='KILLED' ", '');
  assert.deepEqual(
    parseMutations(report(doubleQuoted, noStatus)).map((m) => m.status),
    ['SURVIVED', 'UNKNOWN'],
  );
});

test('a mutation without a source file or line still gets a path and line 1', () => {
  const bare = mutation('SURVIVED').replace('<sourceFile>A.kt</sourceFile>', '').replace('<lineNumber>7</lineNumber>', '');
  const [m] = parseMutations(report(bare));
  assert.equal(m.path, 'app/app/src/main/kotlin/homer/app/config');
  assert.equal(m.line, 1);
});

test('a multi-line method or description stays one finding line', () => {
  const [finding] = pitFindings(parseMutations(report(mutation('SURVIVED', { method: 'run\n', description: 'replaced\n  return value' }))));
  assert.equal(finding.message, 'survived: run → replaced return value');
});

test('a status outside the scored set counts as an error and is no finding', () => {
  const mutations = parseMutations(report(mutation('KILLED'), mutation('SOME_NEW_STATUS'), mutation('NOT_STARTED')));
  assert.deepEqual(pitFindings(mutations), []);
  assert.deepEqual(pitStats(mutations), { score: '100.0', killed: 1, survived: 0, noCoverage: 0, timeout: 0, errors: 2 });
});

test('decodeXml leaves an out-of-range character reference as it is', () => {
  assert.equal(decodeXml('&#x110000;'), '&#x110000;');
});

test('decodeXml inverts XML escaping for any text', () => {
  const escape = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/'/g, '&apos;').replace(/"/g, '&quot;');
  fc.assert(fc.property(fc.string({ unit: 'binary' }), (text) => decodeXml(escape(text)) === text));
});

test('only survived and uncovered mutants become findings, sorted by file and line', () => {
  const mutations = parseMutations(
    report(
      mutation('KILLED'),
      mutation('SURVIVED', { file: 'B.kt', line: 3, method: 'validate', description: 'replaced return value with Collections.emptyList' }),
      mutation('NO_COVERAGE', { file: 'A.kt', line: 9, method: 'size', description: 'replaced long return with 0' }),
      mutation('TIMED_OUT'),
    ),
  );
  assert.deepEqual(pitFindings(mutations), [
    { path: 'app/app/src/main/kotlin/homer/app/config/A.kt', line: 9, message: 'no coverage: size → replaced long return with 0', severity: 'minor' },
    { path: 'app/app/src/main/kotlin/homer/app/config/B.kt', line: 3, message: 'survived: validate → replaced return value with Collections.emptyList', severity: 'minor' },
  ]);
});

test('the score counts killed and timed-out mutants as detected and leaves errors out', () => {
  const statuses = ['KILLED', 'KILLED', 'KILLED', 'TIMED_OUT', 'SURVIVED', 'NO_COVERAGE', 'RUN_ERROR', 'NON_VIABLE'];
  const stats = pitStats(parseMutations(report(...statuses.map((s) => mutation(s)))));
  assert.deepEqual(stats, { score: '66.7', killed: 3, survived: 1, noCoverage: 1, timeout: 1, errors: 2 });
});

test('an empty report has no score', () => {
  assert.equal(pitStats(parseMutations(report())).score, 'n/a');
});

test('removed calls the Kotlin compiler adds are compiler-call mutants, other removed calls are not', () => {
  const removed = (call) => ({ description: `removed call to ${call}` });
  for (const call of ['kotlin/jvm/internal/Intrinsics::checkNotNullExpressionValue', 'kotlin/ResultKt::throwOnFailure', 'kotlin/collections/CollectionsKt::throwIndexOverflow']) {
    assert.equal(isCompilerCallMutant(removed(call)), true, call);
  }
  for (const call of ['kotlin/collections/CollectionsKt::sortWith', 'homer/app/infra/PathSafety::checkContained', 'kotlinx/serialization/json/JsonBuilder::setPrettyPrint']) {
    assert.equal(isCompilerCallMutant(removed(call)), false, call);
  }
  assert.equal(isCompilerCallMutant({ description: 'negated conditional' }), false);
});

test('only a removed call into the Kotlin runtime is ever a compiler-call mutant', () => {
  fc.assert(fc.property(fc.string(), (description) => !isCompilerCallMutant({ description }) || description.startsWith('removed call to kotlin/')));
});

test('every class pattern of the PIT scope names a backend source file', () => {
  const root = path.join(import.meta.dirname, '..', '..');
  const build = fs.readFileSync(path.join(root, 'app/app/build.gradle.kts'), 'utf8');
  const scope = /val nightlyMutationScope =\s*setOf\(([\s\S]*?)\n\s*\)/.exec(build)?.[1];
  assert.ok(scope, 'no nightlyMutationScope in app/app/build.gradle.kts');
  const entries = [...scope.matchAll(/"([^"]*)"/g)].map((m) => m[1]);
  assert.ok(entries.length > 0, 'nightlyMutationScope is empty');
  // Only a package (`a.b.*`) or a class prefix (`a.b.C*`) can be checked against the sources.
  assert.deepEqual(entries.filter((e) => !/^homer\.app\.(?:[a-z]\w*\.)*(?:[A-Z]\w*)?\*$/.test(e)), []);
  const patterns = entries.map((e) => e.slice(0, -1));
  const stale = patterns.filter((p) => {
    const at = path.join(root, 'app/app/src/main/kotlin', ...p.replace(/\.$/, '').split('.'));
    return p.endsWith('.') ? !fs.existsSync(at) : !fs.existsSync(`${at}.kt`);
  });
  assert.deepEqual(stale, []);
});

test('main writes the mutation-backend report files and fails on a missing report', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-'));
  const xml = path.join(dir, 'mutations.xml');
  const nullCheck = mutation('SURVIVED', { description: 'removed call to kotlin/jvm/internal/Intrinsics::checkNotNullParameter' });
  fs.writeFileSync(xml, report(mutation('KILLED'), mutation('SURVIVED'), nullCheck));
  assert.match(main([xml, dir]), /^Backend mutation testing \(score 50\.0%\): 1 finding\(s\)\n/);
  assert.equal(fs.readFileSync(path.join(dir, 'mutation-backend-summary.txt'), 'utf8'), 'findings=1\nscore=50.0\nkilled=1\nsurvived=1\nnoCoverage=0\ntimeout=0\nerrors=0\nignored=1\n');
  assert.throws(() => main([path.join(dir, 'missing.xml'), dir]), /did :app:pitest run\?/);
});
