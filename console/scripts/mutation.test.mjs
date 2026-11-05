import { describe, expect, it } from 'vitest';

import { changedLines } from '../../tools/quality/git-diff.mjs';
import { acceptExceptions, changedRanges, mutantFindings, mutantKey, mutationStats, withinRanges } from './mutation.mjs';

const mutant = (status, line, mutatorName = 'ConditionalExpression', replacement = 'true') => ({
  id: `${status}-${line}`,
  mutatorName,
  replacement,
  status,
  location: { start: { line, column: 2 }, end: { line, column: 9 } },
});

const report = (files) => ({ schemaVersion: '2', thresholds: { high: 80, low: 60 }, files });

describe('changedRanges', () => {
  const always = () => true;
  const diff = (file, ...hunks) => [`diff --git a/${file} b/${file}`, `--- a/${file}`, `+++ b/${file}`, ...hunks].join('\n');

  it('turns the added and changed lines of scored files into mutation ranges', () => {
    const text = [
      diff('console/lib/utils/b.ts', '@@ -3,0 +4,2 @@', '+x', '+y', '@@ -10 +12 @@', '-a', '+b', '@@ -20,3 +23,0 @@'),
      diff('console/app/(dashboard)/agents/[name]/edit/page.tsx', '@@ -7,2 +7,3 @@'),
    ].join('\n');
    expect(changedRanges(changedLines(text), always)).toEqual(['app/(dashboard)/agents/\\[name\\]/edit/page.tsx', 'lib/utils/b.ts:4-5', 'lib/utils/b.ts:12-12']);
  });

  it('mutates a file with glob characters in its path whole and once, since Stryker allows no range there', () => {
    const text = diff('console/app/(dashboard)/agents/[name]/page.tsx', '@@ -1 +1 @@', '@@ -9 +9,2 @@');
    expect(changedRanges(changedLines(text), always)).toEqual(['app/(dashboard)/agents/\\[name\\]/page.tsx']);
  });

  it('drops tests, shadcn primitives, scripts, other stacks and deleted files', () => {
    const text = [
      diff('console/lib/utils/b.test.ts', '@@ -1 +1 @@'),
      diff('console/components/ui/button.tsx', '@@ -1 +1 @@'),
      diff('console/scripts/crap.mjs', '@@ -1 +1 @@'),
      diff('app/app/src/main/kotlin/Main.kt', '@@ -1 +1 @@'),
      diff('console/lib/gone.ts', '@@ -1 +1 @@'),
      ['diff --git a/console/lib/old.ts b/console/lib/old.ts', '--- a/console/lib/old.ts', '+++ /dev/null', '@@ -1,2 +0,0 @@'].join('\n'),
    ].join('\n');
    expect(changedRanges(changedLines(text), (file) => file !== 'lib/gone.ts')).toEqual([]);
  });

  it('treats an empty diff as no changes', () => {
    expect(changedRanges(changedLines(''), always)).toEqual([]);
  });
});

describe('mutantFindings', () => {
  it('reports survived and uncovered mutants with their line and mutator', () => {
    const findings = mutantFindings(
      report({
        'lib/a.ts': {
          language: 'typescript',
          source: '',
          mutants: [mutant('Killed', 1), mutant('Survived', 3, 'EqualityOperator', 'a <= b'), mutant('NoCoverage', 7, 'BlockStatement', '{\n  }')],
        },
      }),
    );
    expect(findings).toEqual([
      { path: 'console/lib/a.ts', line: 3, message: 'survived: EqualityOperator → a <= b', severity: 'minor' },
      { path: 'console/lib/a.ts', line: 7, message: 'no coverage: BlockStatement → { }', severity: 'minor' },
    ]);
  });

  it('shortens a long replacement', () => {
    const [finding] = mutantFindings(report({ 'lib/a.ts': { mutants: [mutant('Survived', 1, 'StringLiteral', 'x'.repeat(100))] } }));
    expect(finding.message).toBe(`survived: StringLiteral → ${'x'.repeat(59)}…`);
  });
});

describe('mutationStats', () => {
  it('counts statuses and scores detected over valid mutants', () => {
    const stats = mutationStats(
      report({
        'lib/a.ts': { mutants: [mutant('Killed', 1), mutant('Killed', 2), mutant('Timeout', 3), mutant('Survived', 4)] },
        'lib/b.ts': { mutants: [mutant('NoCoverage', 1), mutant('CompileError', 2), mutant('Ignored', 3)] },
      }),
    );
    expect(stats).toEqual({ score: '60.0', killed: 2, survived: 1, noCoverage: 1, timeout: 1, errors: 1, ignored: 1 });
  });

  it('has no score without valid mutants', () => {
    expect(mutationStats(report({})).score).toBe('n/a');
  });
});

describe('withinRanges', () => {
  const full = report({
    'lib/a.ts': { mutants: [mutant('Survived', 2), mutant('Survived', 5), mutant('Killed', 6), mutant('NoCoverage', 9)] },
    'lib/cached.ts': { mutants: [mutant('Survived', 1)] },
    'app/(dashboard)/agents/[name]/page.tsx': { mutants: [mutant('Survived', 40)] },
  });

  it('keeps only the changed lines: an incremental report also carries cached files and lines', () => {
    const scoped = withinRanges(full, ['lib/a.ts:5-6']);
    expect(Object.keys(scoped.files)).toEqual(['lib/a.ts']);
    expect(scoped.files['lib/a.ts'].mutants.map((m) => m.location.start.line)).toEqual([5, 6]);
    expect(mutantFindings(scoped)).toHaveLength(1);
    expect(mutationStats(scoped)).toMatchObject({ killed: 1, survived: 1, noCoverage: 0 });
  });

  it('keeps every mutant of a file mutated whole because of its glob characters', () => {
    const scoped = withinRanges(full, ['app/(dashboard)/agents/\\[name\\]/page.tsx']);
    expect(Object.keys(scoped.files)).toEqual(['app/(dashboard)/agents/[name]/page.tsx']);
    expect(scoped.files['app/(dashboard)/agents/[name]/page.tsx'].mutants).toHaveLength(1);
  });

  it('reports nothing for a change whose lines hold no mutant, such as a comment edit', () => {
    expect(mutantFindings(withinRanges(full, ['lib/a.ts:3-4']))).toEqual([]);
    expect(mutationStats(withinRanges(full, ['lib/a.ts:3-4'])).score).toBe('n/a');
  });

  it('matches report keys given as absolute paths', () => {
    const absolute = report({ '/repo/console/lib/a.ts': { mutants: [mutant('Survived', 5)] } });
    expect(mutantFindings(withinRanges(absolute, ['lib/a.ts:5-5'], '/repo/console'))).toHaveLength(1);
  });
});

describe('accepted exceptions', () => {
  const source = 'const a = 1;\nif (x   &&\n  y) {}\n';
  const at = (line, column, endLine, endColumn) => ({ start: { line, column }, end: { line: endLine, column: endColumn } });
  const survivor = (location, replacement = 'x ||  y') => ({ id: 's', mutatorName: 'LogicalOperator', replacement, status: 'Survived', location });
  const KEY = 'lib/a.ts#LogicalOperator#x && y→x || y#if (x &&';

  it('builds a key from the original text of the span, whitespace collapsed, without a line number', () => {
    expect(mutantKey('lib/a.ts', survivor(at(2, 5, 3, 4)), source)).toBe(KEY);
    const moved = `// header\n\n${source}`;
    expect(mutantKey('lib/a.ts', survivor(at(4, 5, 5, 4)), moved)).toBe(KEY);
    expect(mutantKey('/repo/console/lib/a.ts', survivor(at(2, 5, 3, 4)), source, '/repo/console')).toBe(KEY);
  });

  it('gives same-text mutants on different lines different keys, and <unknown> for a missing line', () => {
    const two = 'a = "";\nb = "";\n';
    const first = mutantKey('lib/a.ts', survivor(at(1, 5, 1, 7), '"x"'), two);
    const second = mutantKey('lib/a.ts', survivor(at(2, 5, 2, 7), '"x"'), two);
    expect(first).not.toBe(second);
    expect(mutantKey('lib/a.ts', survivor(at(9, 1, 9, 2)), two)).toMatch(/#<unknown>$/);
  });

  const full = () =>
    report({ 'lib/a.ts': { source, mutants: [mutant('Killed', 1), survivor(at(2, 5, 3, 4)), mutant('Survived', 3, 'StringLiteral', '""')] } });

  it('takes accepted mutants out of the score and the findings', () => {
    const { report: scored, accepted, stale } = acceptExceptions(full(), { [KEY]: 'why' }, { full: true });
    expect(accepted.map((a) => a.reason)).toEqual(['why']);
    expect(stale).toEqual([]);
    expect(mutationStats(scored)).toMatchObject({ killed: 1, survived: 1 });
    expect(mutantFindings(scored)).toHaveLength(1);
  });

  it('reports an entry with a count that matches another number of mutants in a full run only, still accepting them', () => {
    const exceptions = { [KEY]: { reason: 'why', count: 2 } };
    const fullRun = acceptExceptions(full(), exceptions, { full: true });
    expect(fullRun.miscounted).toEqual([{ key: KEY, matched: 1, expected: 2 }]);
    expect(fullRun.accepted.map((a) => a.reason)).toEqual(['why']);
    expect(acceptExceptions(full(), exceptions, { full: false }).miscounted).toEqual([]);
  });

  it('reports a stale key in a full run only, and does not accept a killed mutant', () => {
    expect(acceptExceptions(full(), { 'lib/gone.ts#M#a→b': 'r' }, { full: true }).stale).toEqual(['lib/gone.ts#M#a→b']);
    expect(acceptExceptions(full(), { 'lib/gone.ts#M#a→b': 'r' }, { full: false }).stale).toEqual([]);
  });
});
