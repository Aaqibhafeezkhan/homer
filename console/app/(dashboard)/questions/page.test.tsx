import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { HomerApiError } from '@/lib/api/client';

let mockQuestions: unknown[] = [];
let mockTotal: number | undefined;
let mockFailure: { data: undefined; error: Error } | undefined;
vi.mock('@/lib/api/queries', () => ({
  useQuestions: () =>
    mockFailure ?? {
      data: { questions: mockQuestions, total: mockTotal ?? mockQuestions.length, offset: 0, limit: 100 },
      error: null,
    },
  useAnswerQuestion: () => ({ mutateAsync: vi.fn(), isPending: false, isError: false, error: null }),
  useCancelDispatch: () => ({ mutateAsync: vi.fn(), isPending: false, isError: false, error: null }),
}));

import QuestionsPage from './page';

describe('QuestionsPage', () => {
  beforeEach(() => {
    mockFailure = undefined;
  });

  it('lists open questions with dispatch annotation', () => {
    mockQuestions = [
      {
        id: 'q1',
        processId: 7,
        agentName: 'asker',
        text: 'Approve?',
        options: [],
        status: 'OPEN',
        answer: null,
        createdAt: 1,
        answeredAt: null,
        dispatch: { id: 3, agentName: 'handler', status: 'PENDING' },
      },
    ];
    mockTotal = undefined;
    render(<QuestionsPage />);
    expect(screen.getByText('Approve?')).toBeInTheDocument();
    expect(screen.getByText('handler')).toBeInTheDocument();
    expect(screen.queryByText(/Showing the oldest/)).not.toBeInTheDocument();
  });

  it('says how many open questions wait beyond the page', () => {
    mockQuestions = [
      { id: 'q1', processId: 7, agentName: 'asker', text: 'Approve?', options: [], status: 'OPEN', answer: null, createdAt: 1, answeredAt: null },
    ];
    mockTotal = 101;
    render(<QuestionsPage />);
    expect(screen.getByText(/Showing the oldest 1 of 101 questions/)).toBeInTheDocument();
  });

  it('shows empty state when none', () => {
    mockQuestions = [];
    mockTotal = undefined;
    render(<QuestionsPage />);
    expect(screen.getByText('No open questions.')).toBeInTheDocument();
  });

  it('shows why the inbox could not load instead of an empty inbox', () => {
    mockFailure = { data: undefined, error: new HomerApiError('x', 403, 'Forbidden') };
    render(<QuestionsPage />);
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Failed to load questions: The API rejected the request — sign in, or check the console/backend auth configuration.',
    );
    expect(screen.queryByText('No open questions.')).not.toBeInTheDocument();
  });

  it('names the API URL when the API cannot be reached', () => {
    mockFailure = { data: undefined, error: Object.assign(new HomerApiError('Failed to fetch'), { failure: 'unreachable' as const }) };
    render(<QuestionsPage />);
    expect(screen.getByRole('alert')).toHaveTextContent(/Cannot reach the Homer API at .* corsAllowedOrigins/);
  });
});
