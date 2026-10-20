import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useMarkInAppNavigation, IN_APP_NAV_KEY } from './use-mark-in-app-navigation';

const pathnameMock = vi.hoisted(() => vi.fn());

vi.mock('next/navigation', () => ({
  usePathname: () => pathnameMock(),
}));

describe('useMarkInAppNavigation', () => {
  beforeEach(() => {
    sessionStorage.clear();
    pathnameMock.mockReturnValue('/processes');
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('sets nothing on the first render after a full page load', () => {
    renderHook(() => useMarkInAppNavigation());
    expect(sessionStorage.getItem(IN_APP_NAV_KEY)).toBeNull();
  });

  it('marks in-app navigation when the pathname changes', () => {
    const { rerender } = renderHook(() => useMarkInAppNavigation());
    expect(sessionStorage.getItem(IN_APP_NAV_KEY)).toBeNull();

    pathnameMock.mockReturnValue('/processes/42');
    rerender();
    expect(sessionStorage.getItem(IN_APP_NAV_KEY)).toBe('1');
  });

  it('swallows a sessionStorage error', () => {
    const { rerender } = renderHook(() => useMarkInAppNavigation());
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('sessionStorage unavailable');
    });

    pathnameMock.mockReturnValue('/processes/42');
    expect(() => rerender()).not.toThrow();
  });
});
