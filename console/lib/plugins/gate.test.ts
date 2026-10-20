import { describe, it, expect } from 'vitest';
import { canUsePlugin } from './gate';
import type { Action } from '@/types/homer';

const caps = (isAdmin: boolean, actions: Action[] = []) => ({
  isAdmin,
  can: (action: Action) => isAdmin || actions.includes(action),
});

describe('canUsePlugin', () => {
  it('allows a plugin with no requirement', () => {
    expect(canUsePlugin(undefined, caps(false))).toBe(true);
  });

  it('allows anything for an admin', () => {
    expect(canUsePlugin('admin', caps(true))).toBe(true);
    expect(canUsePlugin('control', caps(true))).toBe(true);
  });

  describe('denials', () => {
    it('denies an admin-only plugin to a non-admin', () => {
      expect(canUsePlugin('admin', caps(false))).toBe(false);
    });

    it('denies an admin-only plugin to a non-admin holding every Action', () => {
      // `admin` is a role, not an action — no combination of grants substitutes.
      expect(canUsePlugin('admin', caps(false, ['run', 'read', 'control', 'edit', 'debug']))).toBe(
        false,
      );
    });

    it('denies an Action-gated plugin when the caller lacks that action', () => {
      expect(canUsePlugin('control', caps(false, ['read']))).toBe(false);
    });
  });

  it('allows an Action-gated plugin when the caller holds the action', () => {
    expect(canUsePlugin('read', caps(false, ['read']))).toBe(true);
  });
});
