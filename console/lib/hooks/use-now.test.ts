import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useNow } from './use-now';

describe('useNow', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-06-10T00:00:00.000Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('seeds the current time in whole seconds on mount', () => {
    const { result } = renderHook(() => useNow());
    expect(result.current).toBe(Math.floor(Date.now() / 1000));
  });

  it('advances once per second', () => {
    const { result } = renderHook(() => useNow());
    const start = result.current;

    act(() => {
      vi.advanceTimersByTime(1000);
    });
    expect(result.current).toBe(start + 1);

    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(result.current).toBe(start + 3);
  });

  it('stays 0 and starts no interval when disabled', () => {
    const { result } = renderHook(() => useNow(false));
    expect(result.current).toBe(0);

    act(() => {
      vi.advanceTimersByTime(3000);
    });
    expect(result.current).toBe(0);
  });

  it('clears the interval on unmount', () => {
    const clearSpy = vi.spyOn(global, 'clearInterval');
    const { unmount } = renderHook(() => useNow());
    unmount();
    expect(clearSpy).toHaveBeenCalled();
  });
});
