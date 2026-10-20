import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

let mockQuestions: unknown[] = [];
let mockTotal: number | undefined;
vi.mock('@/lib/api/queries', () => ({
  useQuestions: () => ({ data: { questions: mockQuestions, total: mockTotal ?? mockQuestions.length, offset: 0, limit: 100 } }),
  useAnswerQuestion: () => ({ mutateAsync: vi.fn(), isPending: false, isError: false, error: null }),
  useCancelDispatch: () => ({ mutateAsync: vi.fn(), isPending: false, isError: false, error: null }),
}));

import QuestionsPage from './page';

describe('QuestionsPage', () => {
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
});
