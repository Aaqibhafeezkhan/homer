import { describe, expect, it } from 'vitest';

import { otherMessages, sizeFindings } from './size.mjs';

const result = (filePath, messages) => ({ filePath, messages });

describe('sizeFindings', () => {
  it('turns each size-rule message into a finding keyed without its line count', () => {
    const results = [
      result('/c/lib/a.ts', [
        { ruleId: 'max-lines-per-function', line: 4, message: "Function 'Big' has too many lines (75). Maximum allowed is 60." },
        { ruleId: 'max-lines', line: 601, message: 'File has too many lines (640). Maximum allowed is 600.' },
      ]),
    ];
    expect(sizeFindings(results, '/c')).toEqual([
      {
        path: 'console/lib/a.ts',
        line: 4,
        message: "max-lines-per-function: Function 'Big' has too many lines (75). Maximum allowed is 60.",
        stableKey: "max-lines-per-function: Function 'Big' has too many lines. Maximum allowed is 60.",
        severity: 'minor',
      },
      {
        path: 'console/lib/a.ts',
        line: 601,
        message: 'max-lines: File has too many lines (640). Maximum allowed is 600.',
        stableKey: 'max-lines: File has too many lines. Maximum allowed is 600.',
        severity: 'minor',
      },
    ]);
  });

  it('ignores messages of any other rule', () => {
    expect(sizeFindings([result('/c/a.ts', [{ ruleId: 'no-console', line: 1, message: 'x' }])], '/c')).toEqual([]);
  });
});

describe('otherMessages', () => {
  it('lists parse errors and other rules, which mean the size run is broken', () => {
    const results = [
      result('/c/a.ts', [
        { ruleId: null, line: 3, message: 'Parsing error: Unexpected token' },
        { ruleId: 'max-lines', line: 9, message: 'File has too many lines (700).' },
      ]),
    ];
    expect(otherMessages(results)).toEqual(['/c/a.ts:3: Parsing error: Unexpected token']);
  });
});
