import { describe, it, expect } from 'vitest';
import { getAgentInputs, getTransformerDescription } from './agent';
import type { AgentInput, AgentParameterTransformer } from '@/types/homer';

const passthrough: AgentParameterTransformer = { type: 'pass' };

const sample: AgentInput = {
  paramName: 'x',
  envName: 'X',
  src: 'query',
  type: 'STRING',
  required: true,
  transformer: passthrough,
};

describe('getAgentInputs', () => {
  it('returns inputs when set', () => {
    const agent = { inputs: [sample], queryParams: null };
    expect(getAgentInputs(agent)).toEqual([sample]);
  });

  it('falls back to queryParams when inputs is null', () => {
    const agent = { inputs: null, queryParams: [sample] };
    expect(getAgentInputs(agent)).toEqual([sample]);
  });

  it('prefers inputs when both present', () => {
    const other: AgentInput = { ...sample, paramName: 'y' };
    const agent = { inputs: [sample], queryParams: [other] };
    expect(getAgentInputs(agent)).toEqual([sample]);
  });

  it('returns empty array when neither set', () => {
    expect(getAgentInputs({ inputs: null })).toEqual([]);
    expect(getAgentInputs({})).toEqual([]);
  });
});

describe('getTransformerDescription', () => {
  it('describes pass-through transformer', () => {
    expect(getTransformerDescription({ type: 'pass' })).toBe('Direct pass-through');
  });

  it('describes a JSONPath transformer by its path', () => {
    expect(getTransformerDescription({ type: 'jsonPath', path: '$.a' })).toBe('Extract JSON path: $.a');
    expect(getTransformerDescription({ type: 'jsonPath' })).toBe('Extract JSON path: unknown');
  });

  it('labels a transformer type it does not know, e.g. from a newer backend', () => {
    const future = { type: 'future' } as unknown as AgentParameterTransformer;
    expect(getTransformerDescription(future)).toBe('Unknown transformer');
  });
});
