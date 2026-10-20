import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

import {
  analyzeFile,
  attributeStatements,
  codeQuality,
  collectScoredFiles,
  configuredThreshold,
  crapScore,
  findUnits,
  formatReport,
  formatSummary,
  isScoredPath,
  parseThreshold,
  violations,
} from './crap.mjs';

const tempRoots = [];

afterEach(() => {
  while (tempRoots.length > 0) fs.rmSync(tempRoots.pop(), { recursive: true, force: true });
});

/** The single unit of `source`'s first function, by name. */
function unit(source, name, fileName = 'x.ts') {
  const found = findUnits(fileName, source).find((u) => u.name === name);
  if (!found) throw new Error(`no unit ${name} in ${findUnits(fileName, source).map((u) => u.name)}`);
  return found;
}

/** An istanbul file entry with one statement per `[line, column, hits]` (line 1-based, column 0-based). */
function fileCoverage(statements) {
  const statementMap = {};
  const s = {};
  statements.forEach(([line, column, hits], i) => {
    statementMap[i] = { start: { line, column }, end: { line, column: null } };
    s[i] = hits;
  });
  return { statementMap, s };
}

const sha1 = (text) => crypto.createHash('sha1').update(text).digest('hex');

describe('findUnits complexity', () => {
  it.each([
    ['if', 'if (a) x();'],
    ['else if', 'if (a) x(); else x();'],
    ['conditional', 'x(a ? 1 : 2);'],
    ['case', 'switch (a) { case 1: x(); break; default: x(); }'],
    ['for', 'for (let i = 0; i < 1; i++) x();'],
    ['for-in', 'for (const k in a) x();'],
    ['for-of', 'for (const k of a) x();'],
    ['while', 'while (a) x();'],
    ['do-while', 'do x(); while (a);'],
    ['catch', 'try { x(); } catch { x(); } finally { x(); }'],
    ['&&', 'x(a && b);'],
    ['||', 'x(a || b);'],
    ['??', 'x(a ?? b);'],
    ['??=', 'a ??= b;'],
  ])('counts one decision point for %s', (_kind, body) => {
    expect(unit(`function f(a, b) { ${body} }`, 'f').cc).toBe(2);
  });

  it('counts else, default, optional chaining, non-null and default parameters as nothing', () => {
    const source = 'function f(a = 1) { a?.b?.(); a!.c; return; }';
    expect(unit(source, 'f').cc).toBe(1);
  });

  it('counts else if as a second if', () => {
    expect(unit('function f(a) { if (a) x(); else if (b) x(); else x(); }', 'f').cc).toBe(3);
  });

  it('scores a nested arrow on its own and leaves its decision points out of the parent', () => {
    const source = 'function outer(a) {\n  if (a) x();\n  a.map((v) => (v ? 1 : v && 2));\n}';
    expect(unit(source, 'outer').cc).toBe(2);
    expect(unit(source, 'outer.map@3').cc).toBe(3);
  });

  it('finds methods, constructors and accessors under their class name', () => {
    const source = 'class C {\n  constructor() { x(); }\n  m() { x(); }\n  get v() { return 1; }\n  set v(n) { x(); }\n  abstractish(): void;\n}';
    expect(findUnits('x.ts', source).map((u) => u.name)).toEqual(['C.constructor', 'C.m', 'C.get v', 'C.set v']);
  });

  it('parses TSX', () => {
    const source = 'export function Page({ a }) {\n  return <div onClick={() => a && go()}>{a ? 1 : 2}</div>;\n}';
    expect(unit(source, 'Page', 'page.tsx').cc).toBe(2);
    expect(unit(source, 'Page.onClick@2', 'page.tsx').cc).toBe(2);
  });

  it('records the first and last line of each unit', () => {
    const source = 'function outer(a) {\n  if (a) x();\n  a.map((v) => v);\n}';
    expect(findUnits('x.ts', source).map((u) => [u.name, u.line, u.endLine])).toEqual([
      ['outer', 1, 4],
      ['outer.map@3', 3, 3],
    ]);
  });

  it('throws on a syntax error', () => {
    expect(() => findUnits('bad.ts', 'function f( {')).toThrow(/bad\.ts: cannot parse/);
  });
});

describe('findUnits naming', () => {
  it('names anonymous units after what holds them, suffixed with their line', () => {
    const source = [
      'const handler = () => x();',
      'function Comp() {',
      '  const v = useMemo(() => 1, []);',
      '  items.map(function () { return 1; });',
      '  const o = { prop: () => 1 };',
      '  this.field = () => 1;',
      '  return () => 2;',
      '}',
    ].join('\n');
    expect(findUnits('x.ts', source).map((u) => u.name)).toEqual([
      'handler@1',
      'Comp',
      'Comp.useMemo@3',
      'Comp.map@4',
      'Comp.prop@5',
      'Comp.field@6',
      'Comp.<lambda>@7',
    ]);
  });

  it('looks through parentheses and type assertions to the holder', () => {
    expect(findUnits('x.ts', 'const f = (() => 1) as unknown;').map((u) => u.name)).toEqual(['f@1']);
  });

  it('chains anonymous parents with their line', () => {
    const source = 'const Foo = () => {\n  items.map((a) =>\n    a.filter((b) => b));\n};';
    expect(findUnits('x.ts', source).map((u) => u.name)).toEqual(['Foo@1', 'Foo@1.map@2', 'Foo@1.map@2.filter@3']);
  });
});

describe('attributeStatements', () => {
  const source = 'function outer() {\n  x();\n  [1].map((v) => {\n    return v;\n  });\n}\nconst top = 1;';

  it('gives each statement to the innermost unit containing its start', () => {
    const units = findUnits('x.ts', source);
    const coverage = fileCoverage([
      [2, 2, 1], // x(); — outer
      [3, 2, 1], // [1].map(...) — outer
      [4, 4, 0], // return v; — the arrow
      [7, 0, 1], // top-level — no unit
    ]);
    expect(units.map((u) => u.name)).toEqual(['outer', 'outer.map@3']);
    expect(attributeStatements(units, source, coverage)).toEqual([
      { statements: 2, covered: 2 },
      { statements: 1, covered: 0 },
    ]);
  });
});

describe('analyzeFile', () => {
  it('leaves a unit without statements unscored', () => {
    const source = 'function a() { x(); }\nfunction b() {}';
    const result = analyzeFile('console/x.ts', source, fileCoverage([[1, 15, 1]]));
    expect(result.unscored).toBe(1);
    expect(result.rows).toEqual([
      { path: 'console/x.ts', line: 1, endLine: 1, start: 0, name: 'a', cc: 1, coverage: 1, crap: 1 },
    ]);
  });

  it('scores every unit with code at 0% when the file is missing from coverage', () => {
    const source = 'function a(p) { if (p) x(); }\nfunction empty() {}\nconst e = () => 1;';
    const result = analyzeFile('console/x.ts', source, undefined);
    expect(result.unscored).toBe(1);
    expect(result.rows.map((r) => [r.name, r.coverage, r.crap])).toEqual([
      ['a', 0, 6],
      ['e@3', 0, 2],
    ]);
  });
});

describe('crapScore and violations', () => {
  it('applies cc² × (1 − cov)³ + cc', () => {
    expect(crapScore(5, 0)).toBe(30);
    expect(crapScore(5, 1)).toBe(5);
    expect(crapScore(4, 0.5)).toBe(6);
  });

  it('flags only scores strictly over the threshold, highest first', () => {
    const rows = [
      { path: 'console/a.ts', start: 0, crap: 30 },
      { path: 'console/a.ts', start: 1, crap: 30.1 },
      { path: 'console/b.ts', start: 0, crap: 90 },
    ];
    expect(violations(rows, 30).map((r) => r.crap)).toEqual([90, 30.1]);
  });

  it('defaults the threshold and rejects a non-number', () => {
    expect(parseThreshold(undefined)).toBe(30);
    expect(parseThreshold('', 25)).toBe(25);
    expect(parseThreshold('12.5', 25)).toBe(12.5);
    expect(() => parseThreshold('lots')).toThrow(/CRAP_THRESHOLD/);
  });

  it('reads the shared threshold from checks.json, falling back to the default', () => {
    expect(configuredThreshold(JSON.stringify({ checks: [{ name: 'x' }, { name: 'crap', threshold: 25 }] }))).toBe(25);
    expect(configuredThreshold(JSON.stringify({ checks: [{ name: 'crap' }] }))).toBe(30);
    expect(configuredThreshold(undefined)).toBe(30);
  });
});

describe('outputs', () => {
  const violation = { path: 'console/lib/a.ts', line: 7, endLine: 12, start: 50, name: 'Foo@3.map@7', cc: 11, coverage: 0.25, crap: 62.046875 };

  it('formats the report', () => {
    expect(formatReport([violation], 12, 3, 30)).toBe(
      'CRAP report: 1 violation(s) over threshold 30 (12 units scored, 3 unscored)\n' +
        '    62.0  cc=11  cov= 25.0%  console/lib/a.ts:7  Foo@3.map@7\n',
    );
  });

  it('writes Code Quality entries with a line-free fingerprint', () => {
    expect(codeQuality([violation], 30)).toEqual([
      {
        description: 'CRAP 62.0 (complexity 11, coverage 25.0%) > 30 in Foo@3.map@7',
        check_name: 'crap',
        fingerprint: sha1('console/lib/a.ts|Foo.map|0'),
        severity: 'major',
        location: { path: 'console/lib/a.ts', lines: { begin: 7, end: 12 } },
      },
    ]);
    expect(codeQuality([{ ...violation, crap: 60 }], 30)[0].severity).toBe('minor');
    expect(codeQuality([], 30)).toEqual([]);
  });

  it('keeps the fingerprint when the unit moves', () => {
    const moved = { ...violation, line: 40, start: 900, name: 'Foo@30.map@40' };
    expect(codeQuality([moved], 30)[0].fingerprint).toBe(codeQuality([violation], 30)[0].fingerprint);
  });

  it('numbers same-file duplicates of a stripped name by their order in the file', () => {
    const second = { ...violation, line: 9, start: 80, name: 'Foo@3.map@9', crap: 99 };
    const entries = codeQuality([second, violation], 30);
    expect(entries.map((e) => e.fingerprint)).toEqual([
      sha1('console/lib/a.ts|Foo.map|1'),
      sha1('console/lib/a.ts|Foo.map|0'),
    ]);
  });

  it('formats the summary as three lines', () => {
    expect(formatSummary(2, 40, 30)).toBe('violations=2\nscored=40\nthreshold=30\n');
  });
});

describe('scored file set', () => {
  it('matches the coverage include/exclude globs', () => {
    expect(isScoredPath('middleware.ts')).toBe(true);
    expect(isScoredPath('app/(dashboard)/page.tsx')).toBe(true);
    expect(isScoredPath('lib/api/client.ts')).toBe(true);
    expect(isScoredPath('lib/api/client.test.ts')).toBe(false);
    expect(isScoredPath('components/ui/button.tsx')).toBe(false);
    expect(isScoredPath('lib/types.d.ts')).toBe(false);
    expect(isScoredPath('scripts/crap.mjs')).toBe(false);
    expect(isScoredPath('types/homer.ts')).toBe(false);
    expect(isScoredPath('next.config.js')).toBe(false);
  });

  it('walks the scored directories', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'homer-crap-'));
    tempRoots.push(root);
    for (const file of ['middleware.ts', 'lib/a/b.ts', 'lib/a/b.test.ts', 'components/ui/c.tsx', 'components/d.tsx', 'types/e.ts']) {
      fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
      fs.writeFileSync(path.join(root, file), '');
    }
    expect(collectScoredFiles(root)).toEqual(['components/d.tsx', 'lib/a/b.ts', 'middleware.ts']);
  });
});
