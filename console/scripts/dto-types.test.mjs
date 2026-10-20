import { describe, expect, it } from 'vitest';

import { extractDeclarations } from './dto-types.mjs';

describe('extractDeclarations', () => {
  it('reads interface fields with line, optionality, nullability and coarse kind', () => {
    const source = [
      'export interface A {',
      '  id: number;',
      '  name?: string | null;',
      '  tags: string[];',
      '  env: Record<string, string>;',
      '  items: Array<B>;',
      '  raw: unknown;',
      '  later: boolean | undefined;',
      '}',
    ].join('\n');
    const { A } = extractDeclarations(source);
    expect(A.line).toBe(1);
    expect(A.kind).toBe('object');
    expect(A.fields).toEqual([
      { name: 'id', line: 2, optional: false, type: { kind: 'number' } },
      { name: 'name', line: 3, optional: true, type: { kind: 'string', nullable: true } },
      { name: 'tags', line: 4, optional: false, type: { kind: 'list', of: { kind: 'string' } } },
      { name: 'env', line: 5, optional: false, type: { kind: 'map', of: { kind: 'string' } } },
      { name: 'items', line: 6, optional: false, type: { kind: 'list', of: { kind: 'ref', name: 'B' } } },
      { name: 'raw', line: 7, optional: false, type: { kind: 'unknown' } },
      { name: 'later', line: 8, optional: true, type: { kind: 'boolean' } },
    ]);
  });

  it('turns string-literal unions into enums and keeps other unions as members', () => {
    const decls = extractDeclarations(
      [
        "type S = 'A' | 'B';",
        "type R = 'admin' | null;",
        "type M = S | 'mixed';",
        "type U = { type: 'x' } | { type: 'y'; reason: string };",
      ].join('\n'),
    );
    expect(decls.S).toEqual({ line: 1, kind: 'enum', values: ['A', 'B'] });
    expect(decls.R).toEqual({ line: 2, kind: 'enum', values: ['admin'], nullable: true });
    expect(decls.M).toEqual({ line: 3, kind: 'union', members: [{ kind: 'ref', name: 'S' }, { kind: 'enum', values: ['mixed'] }] });
    expect(decls.U.kind).toBe('union');
    expect(decls.U.members[1]).toEqual({
      kind: 'object',
      fields: [
        { name: 'type', line: 4, optional: false, type: { kind: 'enum', values: ['y'] } },
        { name: 'reason', line: 4, optional: false, type: { kind: 'string' } },
      ],
    });
  });

  it('reads an index signature as a map and skips functions and constants', () => {
    const decls = extractDeclarations('interface I { [k: string]: number }\nconst X = 1;\nexport function f() {}');
    expect(decls).toEqual({ I: { line: 1, kind: 'map', of: { kind: 'number' } } });
  });

  it('fails on shapes it cannot compare instead of reading them wrong', () => {
    expect(() => extractDeclarations('interface A extends B { x: string }')).toThrow(/extends another/);
    expect(() => extractDeclarations('type P<T> = { v: T };')).toThrow(/generic declaration/);
    expect(() => extractDeclarations('type T = Partial<A>;')).toThrow(/unsupported generic type/);
    expect(() => extractDeclarations('interface A { f(): void }')).toThrow(/unsupported member/);
    expect(() => extractDeclarations('type T = A & B;')).toThrow(/unsupported type/);
    expect(() => extractDeclarations('interface A { x: string; [key: string]: string }')).toThrow(/mixes fields with an index signature/);
    expect(() => extractDeclarations('interface A { [k: string]: string; [n: number]: string }')).toThrow(/multiple index signatures/);
  });
});
