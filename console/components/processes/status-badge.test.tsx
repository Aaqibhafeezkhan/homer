import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { StatusBadge } from './status-badge';
import type { ProcessStatus } from '@/types/homer';

describe('StatusBadge', () => {
  it('exposes the status label as the accessible name', () => {
    const { getByLabelText } = render(<StatusBadge status="WORKING" />);
    expect(getByLabelText('Status: Working')).toBeInTheDocument();
  });

  it('falls back to Unknown for unrecognized status values', () => {
    const { getByLabelText } = render(
      <StatusBadge status={'WAT' as ProcessStatus} />,
    );
    expect(getByLabelText('Status: Unknown')).toBeInTheDocument();
  });

  it('renders the text badge by default', () => {
    const { getByText } = render(<StatusBadge status="FINISHED" />);
    expect(getByText('Finished')).toBeInTheDocument();
  });

  describe('icon variant', () => {
    it('exposes the status label as the accessible name and the tooltip', () => {
      const { getByLabelText } = render(<StatusBadge status="FINISHED" variant="icon" />);
      const icon = getByLabelText('Status: Finished');
      expect(icon).toHaveAttribute('title', 'Finished');
    });

    it('names each status, so the icon alone is not ambiguous', () => {
      const { getByLabelText } = render(<StatusBadge status="KILLED" variant="icon" />);
      expect(getByLabelText('Status: Killed')).toHaveAttribute('title', 'Killed');
    });

    it('renders no text label', () => {
      const { container } = render(<StatusBadge status="FINISHED" variant="icon" />);
      expect(container.textContent).toBe('');
    });

    it('falls back to Unknown for unrecognized status values', () => {
      const { getByLabelText } = render(
        <StatusBadge status={'WAT' as ProcessStatus} variant="icon" />,
      );
      expect(getByLabelText('Status: Unknown')).toHaveAttribute('title', 'Unknown');
    });
  });

  describe('a11y', () => {
    const statuses: ProcessStatus[] = [
      'WORKING',
      'FINISHED',
      'FAILED',
      'KILLED',
      'CREATED',
      'UNKNOWN',
    ];

    for (const status of statuses) {
      it(`has no axe violations for status ${status}`, async () => {
        const { container } = render(<StatusBadge status={status} />);
        expect(await axe(container)).toHaveNoViolations();
      });

      it(`has no axe violations for the icon variant of ${status}`, async () => {
        const { container } = render(<StatusBadge status={status} variant="icon" />);
        expect(await axe(container)).toHaveNoViolations();
      });
    }
  });
});
