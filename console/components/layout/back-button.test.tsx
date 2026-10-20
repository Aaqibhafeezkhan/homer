import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { BackButton } from './back-button';
import { IN_APP_NAV_KEY } from '@/lib/hooks/use-mark-in-app-navigation';

const back = vi.fn();
const push = vi.fn();

vi.mock('next/navigation', () => ({
  useRouter: () => ({ back, push }),
}));

describe('BackButton', () => {
  beforeEach(() => {
    back.mockReset();
    push.mockReset();
    sessionStorage.clear();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('renders the label', () => {
    render(<BackButton fallbackHref="/processes" label="Processes" />);
    expect(screen.getByRole('button', { name: 'Processes' })).toBeInTheDocument();
  });

  it('calls router.back() when the in-app-nav sessionStorage key is set', async () => {
    sessionStorage.setItem(IN_APP_NAV_KEY, '1');
    const user = userEvent.setup();
    render(<BackButton fallbackHref="/processes" label="Processes" />);
    await user.click(screen.getByRole('button', { name: 'Processes' }));
    expect(back).toHaveBeenCalledTimes(1);
    expect(push).not.toHaveBeenCalled();
  });

  it('falls back to router.push(fallbackHref) when the in-app-nav key is absent', async () => {
    const user = userEvent.setup();
    render(<BackButton fallbackHref="/processes" label="Processes" />);
    await user.click(screen.getByRole('button', { name: 'Processes' }));
    expect(push).toHaveBeenCalledWith('/processes');
    expect(back).not.toHaveBeenCalled();
  });

  it('falls back to router.push(fallbackHref) when sessionStorage throws', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('sessionStorage unavailable');
    });
    const user = userEvent.setup();
    render(<BackButton fallbackHref="/processes" label="Processes" />);
    await user.click(screen.getByRole('button', { name: 'Processes' }));
    expect(push).toHaveBeenCalledWith('/processes');
    expect(back).not.toHaveBeenCalled();
  });

  describe('a11y', () => {
    it('has no violations', async () => {
      const { container } = render(<BackButton fallbackHref="/processes" label="Processes" />);
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});
