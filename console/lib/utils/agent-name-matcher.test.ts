import { describe, it, expect } from 'vitest';
import { agentNameMatches } from './agent-name-matcher';

describe('agentNameMatches', () => {
  it('star matches everything', () => {
    expect(agentNameMatches('*', 'anything')).toBe(true);
  });
  it('prefix star matches by startsWith', () => {
    expect(agentNameMatches('deploy-*', 'deploy-web')).toBe(true);
    expect(agentNameMatches('deploy-*', 'build-web')).toBe(false);
  });
  it('exact pattern requires equality', () => {
    expect(agentNameMatches('echo', 'echo')).toBe(true);
    expect(agentNameMatches('echo', 'echo2')).toBe(false);
  });
});
