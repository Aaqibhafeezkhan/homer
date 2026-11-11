import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import fc from 'fast-check';
import { codeLineNumbers, codeQuality, codeText, formatSummary, mutationScore, parseSummary, repoRelative, sha1, writeCheck } from './lib.mjs';

test('the mutation score is detected over detected, survived and uncovered', () => {
  assert.equal(mutationScore({ killed: 3, timeout: 1, survived: 1, noCoverage: 1 }), '66.7');
  assert.equal(mutationScore({ killed: 0, timeout: 0, survived: 0, noCoverage: 0 }), 'n/a');
});

test('fingerprints ignore the line, so a finding keeps its identity when it moves', () => {
  const at = (line) => codeQuality('c', [{ path: 'a.kt', line, message: 'm' }])[0].fingerprint;
  assert.equal(at(3), at(30));
  assert.equal(at(3), sha1('c|a.kt|m|0'));
});

test('two equal findings in one file get distinct fingerprints by occurrence', () => {
  const [a, b] = codeQuality('c', [
    { path: 'a.kt', line: 1, message: 'm' },
    { path: 'a.kt', line: 9, message: 'm' },
  ]);
  assert.notEqual(a.fingerprint, b.fingerprint);
  assert.deepEqual(a.location, { path: 'a.kt', lines: { begin: 1 } });
  assert.equal(a.severity, 'minor');
});

test('a stable key replaces the message in the fingerprint', () => {
  const [a] = codeQuality('c', [{ path: 'a.kt', line: 1, message: 'm @1', stableKey: 'k' }]);
  assert.equal(a.fingerprint, sha1('c|a.kt|k|0'));
  assert.equal(a.description, 'm @1');
});

test('summaries round-trip through parseSummary', () => {
  const text = formatSummary(4, { coverage: '71.5', note: 'a=b' });
  assert.equal(text, 'findings=4\ncoverage=71.5\nnote=a=b\n');
  assert.deepEqual(parseSummary(text), { findings: '4', coverage: '71.5', note: 'a=b' });
});

test('writeCheck writes the report, summary and Code Quality files', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'quality-'));
  const report = writeCheck(dir, 'demo', 'Demo', [{ path: 'x.ts', line: 2, message: 'bad' }], { extra: 1 });
  assert.equal(report, 'Demo: 1 finding(s)\nx.ts:2  bad\n');
  assert.equal(fs.readFileSync(path.join(dir, 'demo-summary.txt'), 'utf8'), 'findings=1\nextra=1\n');
  assert.equal(JSON.parse(fs.readFileSync(path.join(dir, 'gl-code-quality-demo.json'), 'utf8'))[0].check_name, 'demo');
  fs.rmSync(dir, { recursive: true });
});

test('repoRelative turns absolute and relative paths into POSIX repo paths', () => {
  assert.equal(repoRelative('/repo', '/repo/app/A.kt'), 'app/A.kt');
  assert.equal(repoRelative('/repo', 'console/b.ts'), 'console/b.ts');
});

test('codeText blanks comments and string literals, keeping code and newlines', () => {
  assert.equal(codeText('fun f() {}'), 'fun f() {}');
  assert.doesNotMatch(codeText('/** fun g( */\nfun f() {}'), /fun g/);
  assert.doesNotMatch(codeText('fun f(x: String = "(") {}'), /"/);
  assert.doesNotMatch(codeText('val r = """\nfun g(\n"""\nval t = 1'), /fun g/);
  assert.match(codeText('val r = """\nfun g(\n"""\nval t = 1'), /val t = 1/);
});

test('codeText leaves a source it cannot lex to a clean end unchanged', () => {
  for (const broken of ['/* never closed\nfun f() = 1', 'val s = "open\nfun f() = 1', 'fun `never closed() = 1']) {
    assert.equal(codeText(broken), broken);
  }
});

test('codeText agrees with codeLineNumbers: a line with code left is a line codeLineNumbers counts', () => {
  fc.assert(
    fc.property(fc.string(), (source) => {
      const code = codeText(source).split('\n');
      const counted = codeLineNumbers(source);
      for (const [n, line] of code.entries()) assert.equal(counted.has(n + 1) || line.trim() === '', true, JSON.stringify(source));
    }),
  );
});
