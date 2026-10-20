import { describe, it, expect, vi, afterEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useBoundaryError } from './use-boundary-error';

describe('useBoundaryError', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('returns the message alone when there is no digest', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { result } = renderHook(() => useBoundaryError(new Error('kaboom')));
    expect(result.current).toBe('kaboom');
  });

  it('appends the digest when Next.js attached one', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const error = Object.assign(new Error('kaboom'), { digest: 'abc123' });
    const { result } = renderHook(() => useBoundaryError(error));
    expect(result.current).toBe('kaboom\n\nDigest: abc123');
  });

  it('falls back to a placeholder for an empty message', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});
    const { result } = renderHook(() => useBoundaryError(new Error('')));
    expect(result.current).toBe('Unknown error');
  });

  it('logs each caught error once, not on every render', () => {
    const log = vi.spyOn(console, 'error').mockImplementation(() => {});
    const first = new Error('first');
    const { rerender } = renderHook(({ error }) => useBoundaryError(error), {
      initialProps: { error: first },
    });
    rerender({ error: first });
    expect(log).toHaveBeenCalledTimes(1);
    expect(log).toHaveBeenCalledWith(first);

    const second = new Error('second');
    rerender({ error: second });
    expect(log).toHaveBeenCalledTimes(2);
    expect(log).toHaveBeenLastCalledWith(second);
  });
});
