import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

import { codeQuality, formatReport, formatSummary, repoPath, sortFindings, writeReports } from './quality-report.mjs';

const sha1 = (text) => crypto.createHash('sha1').update(text).digest('hex');
const finding = (path, line, message = 'm', severity = 'minor') => ({ path, line, message, severity });

const tempRoots = [];

afterEach(() => {
  while (tempRoots.length > 0) fs.rmSync(tempRoots.pop(), { recursive: true, force: true });
});

describe('repoPath', () => {
  it('prefixes a console-relative path with console/', () => {
    expect(repoPath('lib/utils/cn.ts')).toBe('console/lib/utils/cn.ts');
  });

  it('relativizes an absolute path under the console directory', () => {
    expect(repoPath(path.join('/repo/console', 'app', 'page.tsx'), '/repo/console')).toBe('console/app/page.tsx');
  });
});

describe('sortFindings', () => {
  it('puts major findings first, then orders by path and line', () => {
    const sorted = sortFindings([finding('b', 1), finding('a', 9), finding('a', 2), finding('z', 1, 'm', 'major')]);
    expect(sorted.map((f) => `${f.path}:${f.line}`)).toEqual(['z:1', 'a:2', 'a:9', 'b:1']);
  });
});

describe('formatReport', () => {
  it('starts with the title and count, then lists one finding per line', () => {
    const report = formatReport('Check', [finding('console/b.ts', 3, 'second'), finding('console/a.ts', 1, 'first')], '(x)');
    expect(report).toBe('Check: 2 finding(s) (x)\nconsole/a.ts:1  first\nconsole/b.ts:3  second\n');
  });

  it('reports zero findings as a header only', () => {
    expect(formatReport('Check', [])).toBe('Check: 0 finding(s)\n');
  });
});

describe('formatSummary', () => {
  it('writes findings first, then the extra keys in order', () => {
    expect(formatSummary(3, { errors: 0, score: '71.2' })).toBe('findings=3\nerrors=0\nscore=71.2\n');
  });
});

describe('codeQuality', () => {
  it('builds a Code Quality entry whose fingerprint ignores the line', () => {
    const [entry] = codeQuality('check', [finding('console/a.ts', 7, 'rule: text', 'major')]);
    expect(entry).toEqual({
      description: 'rule: text',
      check_name: 'check',
      fingerprint: sha1('console/a.ts|rule: text|0'),
      severity: 'major',
      location: { path: 'console/a.ts', lines: { begin: 7 } },
    });
    expect(codeQuality('check', [finding('console/a.ts', 99, 'rule: text', 'major')])[0].fingerprint).toBe(entry.fingerprint);
  });

  it('fingerprints a stable key instead of the message when a finding has one', () => {
    const entry = (message) => codeQuality('check', [{ ...finding('console/a.ts', 1, message), stableKey: 'rule: f' }])[0];
    expect(entry('rule: f has 61 lines').fingerprint).toBe(sha1('console/a.ts|rule: f|0'));
    expect(entry('rule: f has 75 lines').fingerprint).toBe(entry('rule: f has 61 lines').fingerprint);
  });

  it('tells same-file duplicates apart by their line order', () => {
    const entries = codeQuality('check', [finding('console/a.ts', 9), finding('console/a.ts', 2)]);
    expect(entries.map((e) => [e.location.lines.begin, e.fingerprint])).toEqual([
      [2, sha1('console/a.ts|m|0')],
      [9, sha1('console/a.ts|m|1')],
    ]);
  });
});

describe('writeReports', () => {
  it('writes the summary, report and code-quality files of a check', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'quality-'));
    tempRoots.push(root);
    const report = writeReports('demo', { title: 'Demo', findings: [finding('console/a.ts', 1)], extra: { errors: 2 } }, root);
    const read = (file) => fs.readFileSync(path.join(root, 'demo', file), 'utf8');
    expect(read('demo-report.txt')).toBe(report);
    expect(read('demo-summary.txt')).toBe('findings=1\nerrors=2\n');
    expect(JSON.parse(read('gl-code-quality-demo.json'))).toHaveLength(1);
  });

  it('writes an empty code-quality array when there are no findings', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'quality-'));
    tempRoots.push(root);
    writeReports('demo', { title: 'Demo', findings: [] }, root);
    expect(fs.readFileSync(path.join(root, 'demo', 'gl-code-quality-demo.json'), 'utf8')).toBe('[]\n');
    expect(fs.readFileSync(path.join(root, 'demo', 'demo-summary.txt'), 'utf8')).toBe('findings=0\n');
  });
});
