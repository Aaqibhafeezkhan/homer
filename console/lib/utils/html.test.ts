import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import { serializeForScript } from './html';

describe('serializeForScript', () => {
  it('round-trips a plain object as JSON', () => {
    const value = { apiBaseUrl: 'https://homer.example.com', authEnabled: true };
    expect(JSON.parse(serializeForScript(value))).toEqual(value);
  });

  it('neutralizes a </script> breakout attempt in a string value', () => {
    const out = serializeForScript({ apiBaseUrl: '</script><script>alert(1)</script>' });
    // No literal `<` or `>` survive to close the enclosing tag.
    expect(out).not.toContain('<');
    expect(out).not.toContain('>');
    expect(out).toContain('\\u003c');
    expect(out).toContain('\\u003e');
    // Still parses back to the original once the escapes are decoded.
    expect(JSON.parse(out).apiBaseUrl).toBe('</script><script>alert(1)</script>');
  });

  it('escapes U+2028 / U+2029 line terminators', () => {
    const value = { apiBaseUrl: 'a' + String.fromCharCode(0x2028) + 'b' + String.fromCharCode(0x2029) + 'c' };
    const out = serializeForScript(value);
    expect(out).not.toContain(String.fromCharCode(0x2028));
    expect(out).not.toContain(String.fromCharCode(0x2029));
    expect(out).toContain('\\u2028');
    expect(out).toContain('\\u2029');
    expect(JSON.parse(out).apiBaseUrl).toBe(value.apiBaseUrl);
  });
});

describe('serializeForScript (property-based)', () => {
  // Strings biased towards the characters an inline <script> cares about.
  const scriptChars = fc.oneof(
    fc.constantFrom('<', '>', '/', '!', '-', '"', '\\', String.fromCharCode(0x2028), String.fromCharCode(0x2029)),
    fc.string({ unit: 'binary', minLength: 1, maxLength: 1 }),
  );
  const values = fc.jsonValue({ stringUnit: scriptChars });

  it('never emits a character that could close the tag or end a JS line', async () => {
    await fc.assert(
      fc.property(values, (value) => {
        expect(serializeForScript(value)).not.toMatch(/[<>\u2028\u2029]/);
      }),
    );
  });

  it('parses back to what JSON.stringify carries and compiles as a script expression', async () => {
    await fc.assert(
      fc.property(values, (value) => {
        const out = serializeForScript(value);
        expect(JSON.parse(out)).toEqual(JSON.parse(JSON.stringify(value)));
        expect(() => new Function(`return (${out});`)).not.toThrow();
      }),
    );
  });
});
