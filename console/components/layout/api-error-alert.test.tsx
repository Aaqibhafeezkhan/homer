import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { HomerApiError } from '@/lib/api/client';
import { ApiErrorAlert } from './api-error-alert';

vi.mock('@/lib/instances/store', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/instances/store')>()),
  activeBaseUrl: () => 'http://localhost:18080',
}));

describe('ApiErrorAlert', () => {
  it('announces the title and the described cause as an alert', () => {
    render(<ApiErrorAlert title="Failed to load questions" error={new HomerApiError('x', 403, 'Forbidden')} />);
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Failed to load questions: The API rejected the request — sign in, or check the console/backend auth configuration.',
    );
  });

  it('uses the caller\'s not-found sentence for a 404', () => {
    render(<ApiErrorAlert title="Failed" error={new HomerApiError('x', 404)} notFound="Process 3 does not exist." />);
    expect(screen.getByRole('alert')).toHaveTextContent('Failed: Process 3 does not exist.');
  });

  describe('a11y', () => {
    it('has no violations', async () => {
      const { container } = render(<ApiErrorAlert title="Failed" error={new HomerApiError('Failed to fetch')} />);
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});
