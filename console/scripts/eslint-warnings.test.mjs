import { describe, expect, it } from 'vitest';

import { errorCount, warningFindings } from './eslint-warnings.mjs';

const result = (filePath, messages) => ({
  filePath,
  messages,
  errorCount: messages.filter((m) => m.severity === 2).length,
  warningCount: messages.filter((m) => m.severity === 1).length,
});

describe('warningFindings', () => {
  it('turns each warning into a repo-relative finding prefixed with its rule', () => {
    const results = [result('/c/lib/a.ts', [{ ruleId: 'no-restricted-globals', severity: 1, message: "Unexpected use of 'fetch'.", line: 4 }])];
    expect(warningFindings(results, '/c')).toEqual([
      { path: 'console/lib/a.ts', line: 4, message: "no-restricted-globals: Unexpected use of 'fetch'.", severity: 'minor' },
    ]);
  });

  it('skips errors, which npm run lint already fails on', () => {
    const results = [result('/c/a.ts', [{ ruleId: 'x', severity: 2, message: 'e', line: 1 }])];
    expect(warningFindings(results, '/c')).toEqual([]);
  });

  it('falls back to line 1 and an eslint rule id for file-level warnings', () => {
    const results = [result('/c/a.ts', [{ ruleId: null, severity: 1, message: 'File ignored.' }])];
    expect(warningFindings(results, '/c')).toEqual([{ path: 'console/a.ts', line: 1, message: 'eslint: File ignored.', severity: 'minor' }]);
  });
});

describe('errorCount', () => {
  it('sums the errors of every file', () => {
    const results = [
      result('/c/a.ts', [{ severity: 2 }, { severity: 1 }]),
      result('/c/b.ts', [{ severity: 2 }]),
    ];
    expect(errorCount(results)).toBe(2);
  });
});
