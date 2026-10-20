import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { AuthModeToggle } from './auth-mode-toggle';

describe('AuthModeToggle', () => {
  it.each([
    ['cookie', 'Password', 'Token'],
    ['bearer', 'Token', 'Password'],
  ] as const)('marks only the %s mode as checked', (mode, checked, unchecked) => {
    render(<AuthModeToggle mode={mode} onModeChange={vi.fn()} />);
    expect(screen.getByRole('radiogroup', { name: 'Authentication mode' })).toBeInTheDocument();
    expect(screen.getByRole('radio', { name: checked })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('radio', { name: unchecked })).toHaveAttribute('aria-checked', 'false');
  });

  it('reports the mode picked', async () => {
    const user = userEvent.setup();
    const onModeChange = vi.fn();
    render(<AuthModeToggle mode="cookie" onModeChange={onModeChange} />);
    await user.click(screen.getByRole('radio', { name: 'Token' }));
    await user.click(screen.getByRole('radio', { name: 'Password' }));
    expect(onModeChange.mock.calls).toEqual([['bearer'], ['cookie']]);
  });

  it('does not submit the surrounding form', async () => {
    const user = userEvent.setup();
    const onSubmit = vi.fn((e: React.FormEvent) => e.preventDefault());
    render(
      <form onSubmit={onSubmit}>
        <AuthModeToggle mode="cookie" onModeChange={vi.fn()} />
      </form>,
    );
    await user.click(screen.getByRole('radio', { name: 'Token' }));
    expect(onSubmit).not.toHaveBeenCalled();
  });
});
