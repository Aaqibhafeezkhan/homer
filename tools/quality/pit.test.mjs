import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { mock, test } from 'node:test';
import fc from 'fast-check';
import { withinChanged } from './pit-changed.mjs';
import { declaresUnitReturn, decodeXml, isCompilerCallMutant, isNullReturnOnUnit, main, mutationKey, parseMutations, pitFindings, pitStats, sourceReader, withKeys, writeReport } from './pit.mjs';

const mutation = (status, { file = 'A.kt', cls = 'homer.app.config.A', method = 'run', line = 7, descriptor = '()V', description = 'negated conditional' } = {}) =>
  `<mutation detected='${status === 'KILLED'}' status='${status}' numberOfTestsRun='1'><sourceFile>${file}</sourceFile>` +
  `<mutatedClass>${cls}</mutatedClass><mutatedMethod>${method}</mutatedMethod><methodDescription>${descriptor}</methodDescription>` +
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
    descriptor: '()V',
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

const summaryOf = (dir) => fs.readFileSync(path.join(dir, 'mutation-backend-summary.txt'), 'utf8');

test('a null-return mutant on a Unit-returning lambda is ignored, a value-returning one stays a finding', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-unit-lambda-'));
  const lambda = (descriptor) => mutation('SURVIVED', { method: 'json$lambda$0', descriptor, description: 'replaced return value with null for homer/app/config/A::json$lambda$0#}' });
  const all = withKeys(parsed(lambda('(Lkotlinx/serialization/json/JsonBuilder;)Lkotlin/Unit;'), lambda('()Ljava/lang/String;')), lineOf('a'));
  const text = writeReport(dir, 'T', all, {});
  assert.match(text, /\(score 0\.0%\): 1 finding\(s\)/);
  assert.match(text, /survived: json\$lambda\$0 → replaced return value with null/);
  assert.match(summaryOf(dir), /findings=1\n/);
  assert.match(summaryOf(dir), /ignored=1\n/);
});

test('an ignored null-return mutant leaves the score, not only the findings', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-unit-fun-'));
  const unit = mutation('SURVIVED', { description: 'replaced return value with null for homer/app/config/A::run#}' });
  const text = writeReport(dir, 'T', withKeys(parsed(mutation('KILLED'), unit), lineOf('a')), {});
  assert.match(text, /\(score 100\.0%\): 0 finding\(s\)/);
  assert.match(summaryOf(dir), /ignored=1\n/);
});

test('a null-return mutant on a suspend function is ignored only when its declaration returns Unit', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-unit-suspend-'));
  const suspend = (method) => mutation('SURVIVED', { method, descriptor: '(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;', description: `replaced return value with null for homer/app/config/A::${method}#private suspend fun ${method}(` });
  const unit = withKeys(parsed(suspend('tick')), lineOf('private suspend fun tick(delay: Long) {', '  delay(delay)', '}'));
  assert.match(writeReport(dir, 'T', unit, {}), /0 finding\(s\)/);
  assert.match(summaryOf(dir), /ignored=1\n/);
  const value = withKeys(parsed(suspend('admitted')), lineOf('private suspend fun admitted(', '): Boolean {'));
  assert.match(writeReport(dir, 'T', value, {}), /1 finding\(s\)/);
  assert.match(summaryOf(dir), /ignored=0\n/);
});

test('the Unit branches of the suspend decision: every body form but a block or : Unit keeps the mutant', () => {
  const suspend = (method) => mutation('SURVIVED', { method, descriptor: '(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;', description: `replaced return value with null for homer/app/config/A::${method}#private suspend fun ${method}(` });
  const unit = (lines) => withKeys(parsed(suspend('tick')), lines)[0].unitReturn;
  // Kept: an inferred `=` body, an overload the name cannot tell apart, a parameter list that never
  // closes, a where clause before an inferred body, and a source the reader cannot answer.
  assert.equal(unit(lineOf('private suspend fun tick() = Unit')), false);
  assert.equal(unit(lineOf('private suspend fun tick(x: Int) {', 'private suspend fun tick(x: Long) {')), false);
  assert.equal(unit(lineOf('private suspend fun tick(delay: Long')), false);
  assert.equal(unit(lineOf('private suspend fun <T> tick(x: T) where T : Any = x')), false);
  assert.equal(unit(() => null), false);
  // Ignored: nested parentheses in a default argument close right, and a where clause can precede
  // the block body of a Unit function.
  assert.equal(unit(lineOf('private suspend fun tick(delay: Long = wait(1)) {')), true);
  assert.equal(unit(lineOf('private suspend fun <T> tick(x: T) where T : Any {')), true);
});

test('the mutated method name never decides: an invokeSuspend mutant is not ignored without a Unit declaration', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-unit-invoke-'));
  const invoke = mutation('SURVIVED', { method: 'invokeSuspend', descriptor: '(Lkotlin/coroutines/Continuation;)Ljava/lang/Object;', description: 'replaced return value with null for homer/app/config/A$1::invokeSuspend#}' });
  const value = writeReport(dir, 'T', withKeys(parsed(invoke), lineOf('private suspend fun invokeSuspend(): Boolean {')), {});
  assert.match(value, /1 finding\(s\)/);
  assert.match(summaryOf(dir), /ignored=0\n/);
  // A suspend lambda's invokeSuspend has no declaration to read, whatever its body returns.
  const lambda = mutation('SURVIVED', { method: 'invokeSuspend', descriptor: '(Ljava/lang/Object;)Ljava/lang/Object;', description: 'replaced return value with null for homer/app/config/A$1::invokeSuspend#}' });
  const noDeclaration = writeReport(dir, 'T', withKeys(parsed(lambda), lineOf('val x = 1')), {});
  assert.match(noDeclaration, /1 finding\(s\)/);
  assert.match(summaryOf(dir), /ignored=0\n/);
});

test('another replaced return on a Unit function is not ignored', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-unit-other-'));
  const other = mutation('SURVIVED', { description: 'replaced return value with Collections.emptyMap for homer/app/config/A::run#' });
  const text = writeReport(dir, 'T', withKeys(parsed(other), lineOf('a')), {});
  assert.match(text, /1 finding\(s\)/);
  assert.match(summaryOf(dir), /ignored=0\n/);
});

test('only a replaced-return-with-null description is ever ignored by the Unit rule', () => {
  fc.assert(fc.property(fc.string(), fc.boolean(), (description, unitReturn) => !isNullReturnOnUnit({ description, unitReturn }) || description.startsWith('replaced return value with null')));
});

test('a declaration returns Unit only when its own return type is Unit', () => {
  const type = fc.tuple(fc.stringMatching(/^[A-Za-z]\w{0,12}$/), fc.boolean()).map(([name, nullable]) => `${name}${nullable ? '?' : ''}`);
  fc.assert(
    fc.property(type, (declared) => {
      assert.equal(declaresUnitReturn([`fun f(): ${declared} {`], 'f'), declared === 'Unit');
    }),
  );
});

test('main writes the mutation-backend report files and fails on a missing report', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-'));
  const xml = path.join(dir, 'mutations.xml');
  const nullCheck = mutation('SURVIVED', { description: 'removed call to kotlin/jvm/internal/Intrinsics::checkNotNullParameter' });
  fs.writeFileSync(xml, report(mutation('KILLED'), mutation('SURVIVED'), nullCheck));
  assert.match(main([xml, dir]), /^Backend mutation testing \(score 50\.0%\): 1 finding\(s\)\n/);
  assert.equal(fs.readFileSync(path.join(dir, 'mutation-backend-summary.txt'), 'utf8'), 'findings=1\nscore=50.0\nkilled=1\nsurvived=1\nnoCoverage=0\ntimeout=0\nerrors=0\nignored=1\naccepted=0\n');
  assert.throws(() => main([path.join(dir, 'missing.xml'), dir]), /did :app:pitest run\?/);
});

const PIT = 'app/app/src/main/kotlin/homer/app/config/A.kt';
const lineOf = (...text) => (file) => (file === PIT ? text.join('\n') : null);
const keyOfSource = 'homer/app/config/A.kt#run#negated conditional#if (x && y) {';
const parsed = (...ms) => parseMutations(report(...ms));

test('the exception key holds the line text, not its number, and is the same when the mutant moves', () => {
  const [a] = withKeys(parsed(mutation('SURVIVED', { line: 2 })), lineOf('', '  if (x   &&  y) {'));
  const [b] = withKeys(parsed(mutation('SURVIVED', { line: 4 })), lineOf('', '', '', 'if (x && y) {', ''));
  assert.equal(a.key, keyOfSource);
  assert.equal(b.key, keyOfSource);
});

test('the key survives lines inserted above the mutant and changes with the text of its own line', () => {
  const line = fc.string().filter((text) => !text.includes('\n'));
  const lines = fc.array(line, { maxLength: 5 });
  fc.assert(
    fc.property(lines, line, lines, lines, line, (above, text, below, inserted, other) => {
      const source = (...rows) => (file) => (file === PIT ? `${rows.join('\n')}\n` : null);
      const m = { path: PIT, line: above.length + 1, method: 'run', description: 'negated conditional' };
      const key = mutationKey(m, source(...above, text, ...below));
      assert.equal(mutationKey({ ...m, line: m.line + inserted.length }, source(...inserted, ...above, text, ...below)), key);
      const collapsed = (t) => t.replace(/\s+/g, ' ').trim();
      if (collapsed(other) !== collapsed(text)) assert.notEqual(mutationKey(m, source(...above, other, ...below)), key);
    }),
  );
});

test('a line past the end of the file, or an unreadable file, is keyed <inline>', () => {
  const [past, unreadable] = withKeys(parsed(mutation('SURVIVED', { line: 3 }), mutation('SURVIVED', { line: 1, file: 'B.kt' })), lineOf('a', 'b', ''));
  assert.equal(past.key, 'homer/app/config/A.kt#run#negated conditional#<inline>');
  assert.equal(unreadable.key, 'homer/app/config/B.kt#run#negated conditional#<inline>');
});

test('an exception covers only the mutants on its own line text, not another one of the method', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-line-'));
  const all = withKeys(parsed(mutation('SURVIVED', { line: 1 }), mutation('SURVIVED', { line: 2 })), lineOf('if (x && y) {', 'if (z) {'));
  const text = writeReport(dir, 'T', all, { exceptions: { 'homer/app/config/A.kt#run#negated conditional#if (x && y) {': 'why' }, full: true });
  assert.match(text, /1 finding\(s\)\n.*:2 {2}survived/);
  assert.match(fs.readFileSync(path.join(dir, 'mutation-backend-summary.txt'), 'utf8'), /accepted=1\n/);
});

test('a full and a changed-lines run give the same key for a source outside its package directory', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-root-'));
  fs.mkdirSync(path.join(root, 'elsewhere'));
  fs.writeFileSync(path.join(root, 'elsewhere', 'A.kt'), 'package homer.app.config\n\nif (x) {\n');
  const inRoot = sourceReader(root);
  const read = (pit) => inRoot(pit.replace('app/app/src/main/kotlin', root));
  const [full] = withKeys(parsed(mutation('SURVIVED', { line: 3 })), read);
  assert.equal(full.key, 'homer/app/config/A.kt#run#negated conditional#if (x) {');
  const lines = new Map([[full.path, { path: 'app/app/src/main/kotlin/homer/app/elsewhere/A.kt', lines: new Set([3]) }]]);
  const [changed] = withinChanged([full], lines);
  assert.equal(changed.path, 'app/app/src/main/kotlin/homer/app/elsewhere/A.kt');
  assert.equal(changed.key, full.key);
  assert.equal(read(`${root}/homer/app/config/missing.kt`), null);
});

test('a stale exception is a finding in a full run only', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-stale-'));
  const all = withKeys(parsed(mutation('KILLED')), lineOf('a'));
  const exceptions = { 'gone.kt#m#d': 'reason' };
  assert.match(writeReport(dir, 'T', all, { exceptions, full: true }), /tools\/quality\/mutation-exceptions\/backend\.json:1 {2}stale accepted exception: gone\.kt#m#d/);
  assert.match(writeReport(dir, 'T', all, { exceptions, full: false }), /0 finding\(s\)/);
});

test('an <inline> exception matching another number of mutants than its count is a finding in a full run only', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-inline-'));
  const all = withKeys(parsed(mutation('SURVIVED', { line: 9 }), mutation('SURVIVED', { line: 9 }), mutation('KILLED')), lineOf('a'));
  const key = 'homer/app/config/A.kt#run#negated conditional#<inline>';
  const summary = () => fs.readFileSync(path.join(dir, 'mutation-backend-summary.txt'), 'utf8');
  assert.match(writeReport(dir, 'T', all, { exceptions: { [key]: { reason: 'why', count: 2 } }, full: true }), /\(score 100\.0%\): 0 finding\(s\)/);
  assert.match(summary(), /accepted=2\n/);
  const miscounted = writeReport(dir, 'T', all, { exceptions: { [key]: { reason: 'why', count: 1 } }, full: true });
  assert.match(miscounted, /\(score 100\.0%\): 1 finding\(s\)\n/);
  assert.match(miscounted, /tools\/quality\/mutation-exceptions\/backend\.json:1 {2}accepted exception matched 2 mutants, expected 1: homer\/app\/config\/A\.kt#run#negated conditional#<inline>/);
  assert.match(summary(), /accepted=2\n/);
  assert.match(writeReport(dir, 'T', all, { exceptions: { [key]: { reason: 'why', count: 1 } }, full: false }), /0 finding\(s\)/);
});

test('the source reader reads a file once, however many mutants it keys', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-cache-'));
  fs.mkdirSync(path.join(root, 'homer/app/config'), { recursive: true });
  fs.writeFileSync(path.join(root, 'homer/app/config/A.kt'), 'package homer.app.config\n\nif (x) {\n');
  const inRoot = sourceReader(root);
  const read = (pit) => inRoot(pit.replace('app/app/src/main/kotlin', root));
  const readFileSync = mock.method(fs, 'readFileSync');
  try {
    const keyed = withKeys(parsed(...Array.from({ length: 50 }, () => mutation('SURVIVED', { line: 3 }))), read);
    assert.ok(keyed.every((m) => m.key.endsWith('#if (x) {')));
    assert.equal(readFileSync.mock.calls.filter(({ arguments: [file] }) => String(file).endsWith('A.kt')).length, 1);
  } finally {
    readFileSync.mock.restore();
  }
});

test('extra summary values follow the counts', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-extra-'));
  writeReport(dir, 'T', withKeys(parsed(mutation('KILLED')), lineOf('a')), { extra: { partial: 'homer.app.x' } });
  assert.match(fs.readFileSync(path.join(dir, 'mutation-backend-summary.txt'), 'utf8'), /accepted=0\npartial=homer\.app\.x\n$/);
});

test('writeReport lists the accepted mutants with their reason in mutation-backend-accepted.txt, and main reports them from the XML', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-accepted-'));
  const all = withKeys(parsed(mutation('SURVIVED', { line: 1 }), mutation('SURVIVED', { line: 2 })), lineOf('if (x && y) {', 'if (z) {'));
  const accepted = path.join(dir, 'mutation-backend-accepted.txt');
  writeReport(dir, 'T', all, { exceptions: { [keyOfSource]: 'because' }, full: true });
  assert.equal(fs.readFileSync(accepted, 'utf8'), `${keyOfSource}  — because\n`);
  writeReport(dir, 'T', all, { full: true });
  assert.equal(fs.readFileSync(accepted, 'utf8'), '');
  const xml = path.join(dir, 'mutations.xml');
  fs.writeFileSync(xml, report(mutation('KILLED')));
  main([xml, dir]);
  assert.equal(fs.readFileSync(accepted, 'utf8'), '');
});
