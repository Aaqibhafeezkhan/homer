import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ActionErrorAlert } from './action-error-alert';

describe('ActionErrorAlert', () => {
  it('renders nothing while the action has not failed', () => {
    const { container } = render(<ActionErrorAlert action="Retry" isError={false} error={new Error('boom')} />);
    expect(container).toBeEmptyDOMElement();
  });

  it("names the action and shows the error's message", () => {
    render(<ActionErrorAlert action="Retry" isError error={new Error('agent is gone')} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Retry failed: agent is gone');
  });

  it('falls back to a generic message for a non-Error failure', () => {
    render(<ActionErrorAlert action="Resume" isError error="nope" />);
    expect(screen.getByRole('alert')).toHaveTextContent('Resume failed: Unknown error');
  });
});
