import { describe, expect, it } from 'vitest';

import { knipFindings } from './unused.mjs';

describe('knipFindings', () => {
  it('turns every issue type into findings and counts them', () => {
    const output = {
      issues: [
        { file: 'lib/old.ts', files: [{ name: 'lib/old.ts' }], exports: [], types: [] },
        {
          file: 'package.json',
          dependencies: [{ name: 'left-pad', line: 12, col: 6 }],
          devDependencies: [{ name: 'tool', line: 40, col: 6 }],
        },
        {
          file: 'lib/a.ts',
          exports: [{ name: 'unusedFn', line: 3, col: 17 }],
          types: [{ name: 'Shape', line: 9, col: 13 }],
          duplicates: [[{ name: 'a', line: 1 }, { name: 'default', line: 5 }]],
        },
      ],
    };
    const { findings, counts } = knipFindings(output);
    expect(findings).toEqual([
      { path: 'console/lib/old.ts', line: 1, message: 'unused file', severity: 'major' },
      { path: 'console/package.json', line: 12, message: 'unused dependency left-pad', severity: 'major' },
      { path: 'console/package.json', line: 40, message: 'unused devDependency tool', severity: 'major' },
      { path: 'console/lib/a.ts', line: 3, message: 'unused export unusedFn', severity: 'minor' },
      { path: 'console/lib/a.ts', line: 9, message: 'unused exported type Shape', severity: 'minor' },
      { path: 'console/lib/a.ts', line: 1, message: 'duplicate export a / default', severity: 'minor' },
    ]);
    expect(counts).toMatchObject({ files: 1, dependencies: 1, devDependencies: 1, exports: 1, types: 1, duplicates: 1, unlisted: 0 });
  });

  it('reports nothing for a clean project', () => {
    const { findings, counts } = knipFindings({ issues: [] });
    expect(findings).toEqual([]);
    expect(Object.values(counts).every((n) => n === 0)).toBe(true);
  });
});
