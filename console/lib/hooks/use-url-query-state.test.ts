import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook } from '@testing-library/react';
import { useUrlQueryState } from './use-url-query-state';

const replace = vi.fn();
const searchParamsMock = vi.hoisted(() => vi.fn());

vi.mock('next/navigation', () => ({
  useRouter: () => ({ replace }),
  usePathname: () => '/processes',
  useSearchParams: () => searchParamsMock(),
}));

function setup(initial: string) {
  searchParamsMock.mockReturnValue(new URLSearchParams(initial));
  return renderHook(() => useUrlQueryState());
}

describe('useUrlQueryState', () => {
  beforeEach(() => {
    replace.mockReset();
  });

  it('sets a new param and replaces the URL with scroll disabled', () => {
    const { result } = setup('');
    result.current.setParams({ status: 'FAILED' });
    expect(replace).toHaveBeenCalledWith('/processes?status=FAILED', { scroll: false });
  });

  it('deletes a param when the patch value is null', () => {
    const { result } = setup('status=FAILED&agent=foo');
    result.current.setParams({ status: null });
    expect(replace).toHaveBeenCalledWith('/processes?agent=foo', { scroll: false });
  });

  it('deletes a param when the patch value is an empty string', () => {
    const { result } = setup('status=FAILED&agent=foo');
    result.current.setParams({ status: '' });
    expect(replace).toHaveBeenCalledWith('/processes?agent=foo', { scroll: false });
  });

  it('merges a patch with existing params rather than replacing them wholesale', () => {
    const { result } = setup('status=FAILED');
    result.current.setParams({ agent: 'my-agent' });
    expect(replace).toHaveBeenCalledWith('/processes?status=FAILED&agent=my-agent', {
      scroll: false,
    });
  });

  it('applies multiple patch entries in one replace call', () => {
    const { result } = setup('status=FAILED&agent=foo&offset=50');
    result.current.setParams({ status: null, agent: null, offset: null });
    expect(replace).toHaveBeenCalledWith('/processes', { scroll: false });
    expect(replace).toHaveBeenCalledTimes(1);
  });

  it('replaces with the bare pathname when all params are cleared', () => {
    const { result } = setup('offset=50');
    result.current.setParams({ offset: null });
    expect(replace).toHaveBeenCalledWith('/processes', { scroll: false });
  });

  it('exposes the current searchParams for reading', () => {
    const { result } = setup('status=FAILED');
    expect(result.current.searchParams.get('status')).toBe('FAILED');
  });
});
