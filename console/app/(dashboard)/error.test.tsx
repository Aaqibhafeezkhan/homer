import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import DashboardError from './error';

describe('DashboardError', () => {
  it('renders a heading and the error message', () => {
    render(<DashboardError error={new Error('kaboom')} reset={vi.fn()} />);
    expect(screen.getByRole('heading', { name: /something went wrong/i })).toBeInTheDocument();
    expect(screen.getByText(/kaboom/)).toBeInTheDocument();
  });

  it('calls reset when "Try again" is clicked', () => {
    const reset = vi.fn();
    render(<DashboardError error={new Error('kaboom')} reset={reset} />);
    fireEvent.click(screen.getByRole('button', { name: /try again/i }));
    expect(reset).toHaveBeenCalledTimes(1);
  });

  it('shows the digest when present so a server error can be correlated', () => {
    const error = Object.assign(new Error('kaboom'), { digest: 'abc123' });
    render(<DashboardError error={error} reset={vi.fn()} />);
    expect(screen.getByText(/abc123/)).toBeInTheDocument();
  });
});
