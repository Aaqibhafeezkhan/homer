import type { Capabilities } from './use-can';

/**
 * A `useCan()` result for tests: every capability answers `isAdmin`, unless
 * overridden. Each suite keeps its own `vi.mock('@/lib/auth/use-can')`.
 */
export function fakeCapabilities(
  isAdmin: boolean,
  overrides: Partial<Capabilities> = {},
): Capabilities {
  return {
    isAdmin,
    can: () => isAdmin,
    canSeeProcess: () => isAdmin,
    canKillProcess: () => isAdmin,
    canRetryProcess: () => isAdmin,
    ...overrides,
  };
}
