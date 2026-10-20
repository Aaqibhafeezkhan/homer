import { describe, it, expect, vi } from 'vitest';
import { notifyAuthExpired, onAuthExpired } from './auth-expired-bus';

describe('auth-expired-bus', () => {
  it('fires registered handlers with the reason', () => {
    const handler = vi.fn();
    const unsubscribe = onAuthExpired(handler);
    notifyAuthExpired('http-401');
    expect(handler).toHaveBeenCalledWith('http-401');
    unsubscribe();
  });

  it('unsubscribe stops further deliveries', () => {
    const handler = vi.fn();
    const unsubscribe = onAuthExpired(handler);
    unsubscribe();
    notifyAuthExpired('http-401');
    expect(handler).not.toHaveBeenCalled();
  });

  it('multiple handlers all fire', () => {
    const h1 = vi.fn();
    const h2 = vi.fn();
    const off1 = onAuthExpired(h1);
    const off2 = onAuthExpired(h2);
    notifyAuthExpired('sse-401');
    expect(h1).toHaveBeenCalledWith('sse-401');
    expect(h2).toHaveBeenCalledWith('sse-401');
    off1();
    off2();
  });
});
