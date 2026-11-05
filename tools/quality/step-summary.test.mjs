import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import fc from 'fast-check';
import { MAX_FINDINGS, escapeHtml, main, renderSummary } from './step-summary.mjs';

const summary = 'findings=2\nscore=57.1\nkilled=4\n';
const report = 'Mutation testing: 2 finding(s) (score 57.1%, full run)\nlib/a.ts:3  survived: ConditionalExpression → true\nlib/b.tsx:9  no coverage: JSX → <div/>\n';

test('renders the summary values as a table and the findings collapsed', () => {
  assert.equal(
    renderSummary('Console mutation testing', summary, report),
    [
      '### Console mutation testing',
      '',
      '| findings | score | killed |',
      '| --- | --- | --- |',
      '| 2 | 57.1 | 4 |',
      '',
      '<details><summary>2 finding(s)</summary>',
      '',
      '<pre>lib/a.ts:3  survived: ConditionalExpression → true',
      'lib/b.tsx:9  no coverage: JSX → &lt;div/&gt;</pre>',
      '',
      '</details>',
      '',
    ].join('\n'),
  );
});

test('a report without findings renders only the table', () => {
  const text = renderSummary('PIT', 'findings=0\nscore=100.0\n', 'Backend mutation testing (score 100.0%): 0 finding(s)\n');
  assert.doesNotMatch(text, /<details>/);
  assert.match(text, /\| 0 \| 100\.0 \|/);
});

test('a missing summary says so instead of failing', () => {
  assert.equal(renderSummary('PIT', undefined, undefined), '### PIT\n\nNo report: the run wrote none, see the job log.\n');
});

test(`lists at most ${MAX_FINDINGS} findings and counts the rest`, () => {
  const lines = Array.from({ length: MAX_FINDINGS + 5 }, (_, i) => `a.kt:${i + 1}  survived: m → x`);
  const text = renderSummary('PIT', `findings=${lines.length}\n`, `PIT: ${lines.length} finding(s)\n${lines.join('\n')}\n`);
  assert.match(text, new RegExp(`a\\.kt:${MAX_FINDINGS}  `));
  assert.doesNotMatch(text, new RegExp(`a\\.kt:${MAX_FINDINGS + 1}  `));
  assert.match(text, /… 5 more in the job artifact/);
});

test('table cells cannot break the table or open a tag', () => {
  const text = renderSummary('H', 'findings=1\nnote=a|b<script>\n', undefined);
  assert.match(text, /\| 1 \| a&#124;b&lt;script&gt; \|/);
});

test('escaped finding text holds no tag and decodes back to the original', () => {
  const unescape = (s) => s.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
  fc.assert(
    fc.property(fc.string({ unit: 'binary' }), (text) => {
      const escaped = escapeHtml(text);
      return !/[<>]/.test(escaped) && unescape(escaped) === text;
    }),
  );
});

test('no rendered finding can close the <pre> block or open a tag', () => {
  fc.assert(
    fc.property(fc.array(fc.string({ unit: 'binary' }).map((s) => s.replace(/\n/g, ' ')), { maxLength: 5 }), (messages) => {
      const lines = messages.map((m, i) => `a.ts:${i + 1}  ${m}`);
      const text = renderSummary('H', `findings=${lines.length}\n`, `T: ${lines.length} finding(s)\n${lines.join('\n')}\n`);
      const pre = /<pre>([\s\S]*)<\/pre>/.exec(text)?.[1] ?? '';
      return !/[<>]/.test(pre);
    }),
  );
});

test('main reads the check files from the directory and requires its arguments', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'summary-'));
  fs.writeFileSync(path.join(dir, 'demo-summary.txt'), 'findings=0\n');
  fs.writeFileSync(path.join(dir, 'demo-report.txt'), 'Demo: 0 finding(s)\n');
  assert.equal(main([dir, 'demo', 'Demo']), '### Demo\n\n| findings |\n| --- |\n| 0 |\n');
  assert.throws(() => main([dir, 'demo']), /usage/);
});

test('accepted exceptions render in their own escaped block after the findings', () => {
  const text = renderSummary('PIT', summary, report, 'a.kt#m#x → <b>  — reason & more\n');
  assert.ok(text.indexOf('2 finding(s)') < text.indexOf('1 accepted exception(s)'));
  assert.ok(text.includes('<pre>a.kt#m#x → &lt;b&gt;  — reason &amp; more</pre>'));
  assert.doesNotMatch(renderSummary('PIT', summary, report, ''), /accepted/);
});

test('main reads the accepted file when present', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'summary-accepted-'));
  fs.writeFileSync(path.join(dir, 'demo-summary.txt'), 'findings=0\n');
  fs.writeFileSync(path.join(dir, 'demo-accepted.txt'), 'k  — why\n');
  assert.match(main([dir, 'demo', 'Demo']), /1 accepted exception\(s\)/);
});
