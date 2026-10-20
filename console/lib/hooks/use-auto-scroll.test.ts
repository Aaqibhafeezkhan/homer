import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useAutoScroll } from './use-auto-scroll';

// A sentinel element nested inside a scrollable container. The hook walks up
// from the sentinel to the nearest overflow:auto|scroll ancestor, so we stub
// getComputedStyle to report the container as scrollable and drive its scroll
// geometry to control the pinned-to-bottom decision.
function makeSentinel(geometry: {
  scrollHeight: number;
  scrollTop: number;
  clientHeight: number;
}) {
  const container = document.createElement('div');
  const sentinel = document.createElement('div');
  container.appendChild(sentinel);

  Object.defineProperty(container, 'scrollHeight', { value: geometry.scrollHeight, configurable: true });
  Object.defineProperty(container, 'scrollTop', { value: geometry.scrollTop, configurable: true });
  Object.defineProperty(container, 'clientHeight', { value: geometry.clientHeight, configurable: true });

  const scrollIntoView = vi.fn();
  sentinel.scrollIntoView = scrollIntoView;

  return { container, sentinel, scrollIntoView };
}

describe('useAutoScroll', () => {
  let computedStyleSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    // Report the container (the only element with a parent in our fixtures that
    // is itself parentless) as scrollable; everything else as non-scrollable.
    computedStyleSpy = vi.spyOn(window, 'getComputedStyle').mockImplementation(
      (el: Element) =>
        ({ overflowY: (el as HTMLElement).parentElement ? 'visible' : 'auto' }) as CSSStyleDeclaration
    );
  });

  afterEach(() => {
    computedStyleSpy.mockRestore();
  });

  it('scrolls on dep change when pinned to the bottom', () => {
    // distanceFromBottom = 1000 - 880 - 100 = 20 < 120 → pinned.
    const { sentinel, scrollIntoView } = makeSentinel({ scrollHeight: 1000, scrollTop: 880, clientHeight: 100 });
    const ref = { current: sentinel };

    const { rerender } = renderHook(({ deps }) => useAutoScroll(ref, true, deps), {
      initialProps: { deps: [1] },
    });
    scrollIntoView.mockClear();

    rerender({ deps: [2] });
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'end' });
  });

  it('does not scroll on dep change when scrolled away from the bottom', () => {
    // distanceFromBottom = 1000 - 0 - 100 = 900 ≥ 120 → not pinned.
    const { sentinel, scrollIntoView } = makeSentinel({ scrollHeight: 1000, scrollTop: 0, clientHeight: 100 });
    const ref = { current: sentinel };

    const { rerender } = renderHook(({ deps }) => useAutoScroll(ref, true, deps), {
      initialProps: { deps: [1] },
    });
    scrollIntoView.mockClear();

    rerender({ deps: [2] });
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('walks past non-scrollable ancestors to the scroll container', () => {
    // The sentinel sits in a non-scrollable wrapper inside the container, which is far from the bottom.
    const { container, sentinel, scrollIntoView } = makeSentinel({ scrollHeight: 1000, scrollTop: 0, clientHeight: 100 });
    const wrapper = document.createElement('div');
    container.appendChild(wrapper);
    wrapper.appendChild(sentinel);
    const ref = { current: sentinel };

    const { rerender } = renderHook(({ deps }) => useAutoScroll(ref, true, deps), {
      initialProps: { deps: [1] },
    });
    rerender({ deps: [2] });
    expect(scrollIntoView).not.toHaveBeenCalled();
  });

  it('falls back to scrolling the sentinel into view without a scroll container', () => {
    const sentinel = document.createElement('div');
    const scrollIntoView = vi.fn();
    sentinel.scrollIntoView = scrollIntoView;
    const ref = { current: sentinel };

    renderHook(() => useAutoScroll(ref, true, [1]));
    expect(scrollIntoView).toHaveBeenCalledWith({ block: 'end' });
  });

  it('does nothing while the sentinel is not mounted', () => {
    const ref = { current: null };
    expect(() => renderHook(() => useAutoScroll(ref, true, [1]))).not.toThrow();
  });

  it('does nothing when disabled', () => {
    const { sentinel, scrollIntoView } = makeSentinel({ scrollHeight: 1000, scrollTop: 880, clientHeight: 100 });
    const ref = { current: sentinel };

    const { rerender } = renderHook(({ deps }) => useAutoScroll(ref, false, deps), {
      initialProps: { deps: [1] },
    });
    rerender({ deps: [2] });
    expect(scrollIntoView).not.toHaveBeenCalled();
  });
});
