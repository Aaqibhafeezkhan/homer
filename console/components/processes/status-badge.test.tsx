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
    }
  });
});
