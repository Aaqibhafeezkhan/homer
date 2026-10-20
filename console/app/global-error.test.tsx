import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import GlobalError from './global-error';

describe('GlobalError', () => {
  it('renders a heading and lets the user reload', () => {
    const reset = vi.fn();
    render(<GlobalError error={new Error('fatal')} reset={reset} />);
    expect(screen.getByRole('heading', { name: /something went wrong/i })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /reload/i }));
    expect(reset).toHaveBeenCalledTimes(1);
  });
});
