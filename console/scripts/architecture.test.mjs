import { describe, expect, it } from 'vitest';

import { importLine, violationFindings } from './architecture.mjs';

describe('importLine', () => {
  it('finds the line that quotes the specifier', () => {
    const source = "import a from 'react';\nimport { b } from \"@/lib/b\";\n";
    expect(importLine(source, '@/lib/b')).toBe(2);
  });

  it('does not match a specifier that is only a prefix of another', () => {
    expect(importLine("import x from '@/lib/b/c';\nimport y from '@/lib/b';\n", '@/lib/b')).toBe(2);
  });

  it('falls back to line 1', () => {
    expect(importLine('const x = 1;\n', '@/lib/b')).toBe(1);
  });
});

describe('violationFindings', () => {
  const sources = {
    'lib/a.ts': "import x from 'react';\nimport { c } from '@/components/c';\n",
    'components/c.ts': "import { a } from '@/lib/a';\n",
  };
  const read = (file) => sources[file];

  it('places a dependency violation on the offending import', () => {
    const violations = [
      { type: 'dependency', from: 'lib/a.ts', to: 'components/c.ts', unresolvedTo: '@/components/c', rule: { name: 'lib-not-to-ui', severity: 'warn' } },
    ];
    expect(violationFindings(violations, read)).toEqual([
      { path: 'console/lib/a.ts', line: 2, message: 'lib-not-to-ui: imports components/c.ts', severity: 'minor' },
    ]);
  });

  it('describes a cycle by its full path', () => {
    const violations = [
      {
        type: 'cycle',
        from: 'components/c.ts',
        to: 'lib/a.ts',
        unresolvedTo: '@/lib/a',
        rule: { name: 'no-circular', severity: 'warn' },
        cycle: [{ name: 'lib/a.ts' }, { name: 'components/c.ts' }],
      },
    ];
    expect(violationFindings(violations, read)).toEqual([
      { path: 'console/components/c.ts', line: 1, message: 'no-circular: cycle components/c.ts → lib/a.ts → components/c.ts', severity: 'minor' },
    ]);
  });

  it('marks error-severity rules major and reports module rules at line 1 without reading the source', () => {
    const violations = [{ type: 'module', from: 'lib/a.ts', to: 'lib/a.ts', rule: { name: 'no-orphans', severity: 'error' } }];
    expect(violationFindings(violations, () => {
      throw new Error('not read');
    })).toEqual([{ path: 'console/lib/a.ts', line: 1, message: 'no-orphans: lib/a.ts', severity: 'major' }]);
  });
});
