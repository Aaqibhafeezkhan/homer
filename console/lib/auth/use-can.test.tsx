import { describe, it, expect } from 'vitest';
import { capabilitiesFor } from './use-can';
import type { AuthUser } from './auth-context';

const admin: AuthUser = { username: 'a', role: 'admin', grants: [] };
const scoped: AuthUser = {
  username: 'bob',
  role: null,
  grants: [{ agents: ['deploy-*'], actions: ['run', 'read'] }],
};

describe('capabilitiesFor', () => {
  it('admin can everything', () => {
    const c = capabilitiesFor(admin);
    expect(c.isAdmin).toBe(true);
    expect(c.can('edit', 'anything')).toBe(true);
    expect(c.canKillProcess({ agentName: 'x', owner: null })).toBe(true);
  });
  it('scoped grant matches by action + glob', () => {
    const c = capabilitiesFor(scoped);
    expect(c.can('run', 'deploy-web')).toBe(true);
    expect(c.can('run', 'other')).toBe(false);
    expect(c.can('edit', 'deploy-web')).toBe(false); // no edit action granted
  });
  it('kill requires control (or owner); read+run is not enough', () => {
    const c = capabilitiesFor(scoped);
    expect(c.canKillProcess({ agentName: 'deploy-web', owner: 'session:someoneelse' })).toBe(false);
    // owner branch: bob owns this run -> can kill even without control
    expect(c.canKillProcess({ agentName: 'deploy-web', owner: 'session:bob' })).toBe(true);
  });
  it('retry needs run AND see', () => {
    const c = capabilitiesFor(scoped);
    expect(c.canRetryProcess({ agentName: 'deploy-web', owner: null })).toBe(true); // read gives see, run gives retry
    expect(c.canRetryProcess({ agentName: 'other', owner: null })).toBe(false);
  });
  it('owner alone is not enough to retry without a run grant', () => {
    const c = capabilitiesFor(scoped);
    // bob owns this run but has no `run` grant on agent 'other' -> retry denied.
    expect(c.canRetryProcess({ agentName: 'other', owner: 'session:bob' })).toBe(false);
  });
  it('null user can nothing', () => {
    const c = capabilitiesFor(null);
    expect(c.can('read', 'x')).toBe(false);
    expect(c.canSeeProcess({ agentName: 'x', owner: null })).toBe(false);
  });
  it('unsecured mode allows everything even with no user', () => {
    const c = capabilitiesFor(null, true);
    expect(c.isAdmin).toBe(true);
    expect(c.can('edit', 'anything')).toBe(true);
    expect(c.canKillProcess({ agentName: 'x', owner: null })).toBe(true);
  });
  it('secured mode with no user stays all-false', () => {
    const c = capabilitiesFor(null, false);
    expect(c.isAdmin).toBe(false);
    expect(c.can('edit', 'anything')).toBe(false);
    expect(c.canKillProcess({ agentName: 'x', owner: null })).toBe(false);
  });
});
