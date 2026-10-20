import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useSidebarCollapsed } from './use-sidebar-collapsed';

describe('useSidebarCollapsed', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('defaults to expanded with empty localStorage', () => {
    const { result } = renderHook(() => useSidebarCollapsed());
    expect(result.current.collapsed).toBe(false);
  });

  it('toggle flips the state and persists it', () => {
    const { result } = renderHook(() => useSidebarCollapsed());

    act(() => result.current.toggle());
    expect(result.current.collapsed).toBe(true);
    expect(localStorage.getItem('homer:sidebar-collapsed')).toBe('1');

    act(() => result.current.toggle());
    expect(result.current.collapsed).toBe(false);
    expect(localStorage.getItem('homer:sidebar-collapsed')).toBe('0');
  });

  it('reads a stored collapsed choice back on a fresh mount', () => {
    localStorage.setItem('homer:sidebar-collapsed', '1');
    const { result } = renderHook(() => useSidebarCollapsed());
    expect(result.current.collapsed).toBe(true);
  });

  it('falls back to expanded without throwing when storage is unavailable', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('localStorage unavailable');
    });
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('localStorage unavailable');
    });

    const { result } = renderHook(() => useSidebarCollapsed());
    expect(result.current.collapsed).toBe(false);
    expect(() => act(() => result.current.toggle())).not.toThrow();
  });
});
