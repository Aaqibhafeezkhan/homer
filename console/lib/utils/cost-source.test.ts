import { describe, it, expect } from 'vitest';
import { costReasonText, costSourceLabel, costSourceTitle, stepCostLabel } from './cost-source';

describe('cost-source', () => {
  it('maps each source to a label', () => {
    expect(costSourceLabel('session')).toBe('proxy');
    expect(costSourceLabel('cli')).toBe('CLI estimate');
    expect(costSourceLabel('mixed')).toBe('mixed');
    expect(costSourceLabel('none')).toBe('not reported');
  });

  it('describes the none source as nothing charged', () => {
    expect(costSourceTitle('none')).toMatch(/nothing was charged/);
  });
  it('defaults an absent source to the CLI estimate', () => {
    expect(costSourceLabel(undefined)).toBe('CLI estimate');
    expect(costSourceTitle(undefined)).toMatch(/Claude CLI/);
  });
  it('does not call an incomplete session figure a fallback', () => {
    expect(costReasonText('session', 'incomplete: 3 of 5 calls ingested')).toBe('incomplete: 3 of 5 calls ingested');
  });
  it('marks a CLI figure as a fallback', () => {
    expect(costReasonText('cli', 'http 404')).toBe('fallback: http 404');
    expect(costReasonText(undefined, 'http 404')).toBe('fallback: http 404');
  });
  it('tolerates a costSource value outside the known union', () => {
    expect(costSourceLabel('table')).toBe('table');
    expect(costSourceTitle('table')).toBe('Price source reported by the server.');
  });
});

describe('stepCostLabel', () => {
  it('shows the resolved figure of a priced step', () => {
    expect(stepCostLabel({ sessionId: 's', costUsd: 0.008, source: 'session', fallbackReason: null })).toBe('$0.0080');
    expect(stepCostLabel({ sessionId: 's', costUsd: 0.5, source: 'cli', fallbackReason: 'http 404' }, 0.1)).toBe('$0.5000');
  });

  it('reads "not reported" for a step nobody priced, whatever the CLI figure', () => {
    expect(stepCostLabel({ sessionId: null, costUsd: 0, source: 'none', fallbackReason: null }, 0.3)).toBe('not reported');
  });

  it('falls back to the CLI figure without accounting, and to "not reported" without either', () => {
    expect(stepCostLabel(null, 0.0123)).toBe('$0.0123');
    expect(stepCostLabel(undefined)).toBe('not reported');
  });
});
