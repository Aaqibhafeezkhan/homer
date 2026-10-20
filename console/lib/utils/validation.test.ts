import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import { validateParameterValue, validateAllParameters, isFormValid } from './validation';
import type { AgentQueryParam } from '@/types/homer';

function param(overrides: Partial<AgentQueryParam> = {}): AgentQueryParam {
  return {
    paramName: 'p',
    envName: 'P',
    src: 'query',
    type: 'STRING',
    required: false,
    transformer: { type: 'pass' },
    ...overrides,
  };
}

describe('validateParameterValue', () => {
  it('rejects empty values for required params', () => {
    expect(validateParameterValue(param({ required: true }), '  ')).toEqual({
      isValid: false,
      error: 'This field is required',
    });
  });

  it('accepts empty values for optional params without further checks', () => {
    expect(validateParameterValue(param({ type: 'NUMBER' }), '')).toEqual({ isValid: true });
  });

  it('requires valid JSON when the transformer is jsonPath, skipping type checks', () => {
    const p = param({ type: 'NUMBER', transformer: { type: 'jsonPath', path: '$.x' } });
    expect(validateParameterValue(p, '{"x": "not a number"}')).toEqual({ isValid: true });
    expect(validateParameterValue(p, 'not json')).toEqual({
      isValid: false,
      error: 'Must be valid JSON (JSONPath transformer requires JSON input)',
    });
  });

  it('rejects STRING values containing spaces', () => {
    expect(validateParameterValue(param(), 'a b')).toEqual({
      isValid: false,
      error: 'String values cannot contain spaces',
    });
    expect(validateParameterValue(param(), 'ab')).toEqual({ isValid: true });
  });

  it('rejects non-integer NUMBER values', () => {
    const p = param({ type: 'NUMBER' });
    expect(validateParameterValue(p, '1.5')).toEqual({
      isValid: false,
      error: 'Must be a valid integer',
    });
    expect(validateParameterValue(p, '-42')).toEqual({ isValid: true });
  });

  it('accepts anything non-empty for ANY type', () => {
    expect(validateParameterValue(param({ type: 'ANY' }), 'a b {')).toEqual({ isValid: true });
  });

  it('rejects non-integer integer values', () => {
    const p = param({ type: 'integer' });
    expect(validateParameterValue(p, '1.5')).toEqual({
      isValid: false,
      error: 'Must be a valid integer',
    });
    expect(validateParameterValue(p, '42')).toEqual({ isValid: true });
  });

  it('rejects non-numeric number values', () => {
    const p = param({ type: 'number' });
    expect(validateParameterValue(p, 'abc')).toEqual({
      isValid: false,
      error: 'Must be a valid number',
    });
    expect(validateParameterValue(p, '-1.5')).toEqual({ isValid: true });
  });

  it('rejects boolean values that are not true/false', () => {
    const p = param({ type: 'boolean' });
    expect(validateParameterValue(p, 'yes')).toEqual({
      isValid: false,
      error: "Must be 'true' or 'false'",
    });
    expect(validateParameterValue(p, 'TRUE')).toEqual({ isValid: true });
  });

  it('accepts string values containing spaces on the lowercase string type', () => {
    const p = param({ type: 'string' });
    expect(validateParameterValue(p, 'a b')).toEqual({ isValid: true });
    expect(validateParameterValue(param({ type: 'STRING' }), 'a b')).toEqual({
      isValid: false,
      error: 'String values cannot contain spaces',
    });
    expect(validateParameterValue(p, 'ab')).toEqual({ isValid: true });
  });
});

describe('validateParameterValue properties', () => {
  const types: AgentQueryParam['type'][] = ['STRING', 'NUMBER', 'ANY', 'string', 'integer', 'number', 'boolean'];
  const nonBlank = fc.string({ minLength: 1 }).filter((s) => s.trim() !== '');

  it('returns an error exactly when the value is invalid', () => {
    fc.assert(
      fc.property(fc.constantFrom(...types), fc.boolean(), fc.string(), (type, required, value) => {
        const result = validateParameterValue(param({ type, required }), value);
        expect(result.isValid).toBe(result.error === undefined);
      }),
    );
  });

  it('treats a blank value as missing on a required param and as valid otherwise', () => {
    const blank = fc.stringMatching(/^\s*$/);
    fc.assert(
      fc.property(fc.constantFrom(...types), blank, (type, value) => {
        expect(validateParameterValue(param({ type, required: true }), value)).toEqual({
          isValid: false,
          error: 'This field is required',
        });
        expect(validateParameterValue(param({ type }), value)).toEqual({ isValid: true });
      }),
    );
  });

  it('accepts any non-blank value on the string and ANY types', () => {
    fc.assert(
      fc.property(fc.constantFrom<AgentQueryParam['type']>('string', 'ANY'), nonBlank, (type, value) => {
        expect(validateParameterValue(param({ type }), value)).toEqual({ isValid: true });
      }),
    );
  });

  it('accepts every integer, padded only on the integer type', () => {
    fc.assert(
      fc.property(fc.integer(), (n) => {
        expect(validateParameterValue(param({ type: 'NUMBER' }), String(n))).toEqual({ isValid: true });
        expect(validateParameterValue(param({ type: 'NUMBER' }), ` ${n}`).isValid).toBe(false);
        expect(validateParameterValue(param({ type: 'integer' }), ` ${n} `)).toEqual({ isValid: true });
        expect(validateParameterValue(param({ type: 'number' }), String(n))).toEqual({ isValid: true });
      }),
    );
  });

  it('accepts true and false in any case and padding on the boolean type, and nothing else', () => {
    fc.assert(
      fc.property(nonBlank, (value) => {
        const literal = ['true', 'false'].includes(value.trim().toLowerCase());
        expect(validateParameterValue(param({ type: 'boolean' }), value).isValid).toBe(literal);
      }),
    );
    fc.assert(
      fc.property(fc.constantFrom('true', 'false'), fc.boolean(), (word, upper) => {
        const value = ` ${upper ? word.toUpperCase() : word} `;
        expect(validateParameterValue(param({ type: 'boolean' }), value)).toEqual({ isValid: true });
      }),
    );
  });
});

describe('validateAllParameters', () => {
  it('maps invalid params to their error messages and omits valid ones', () => {
    const params = [
      param({ paramName: 'a', required: true }),
      param({ paramName: 'b', type: 'NUMBER' }),
      param({ paramName: 'c' }),
    ];
    expect(validateAllParameters(params, { b: 'x', c: 'ok' })).toEqual({
      a: 'This field is required',
      b: 'Must be a valid integer',
    });
  });

  it('returns an empty map when everything is valid', () => {
    expect(validateAllParameters([param({ paramName: 'a' })], { a: 'ok' })).toEqual({});
  });
});

describe('isFormValid', () => {
  it('is false when there are existing errors', () => {
    expect(isFormValid([param()], {}, { p: 'oops' })).toBe(false);
  });

  it('is false when a required field is blank', () => {
    expect(isFormValid([param({ required: true })], { p: '  ' }, {})).toBe(false);
  });

  it('is true when there are no errors and all required fields are filled', () => {
    expect(isFormValid([param({ required: true }), param({ paramName: 'q' })], { p: 'x' }, {})).toBe(true);
  });
});
