import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QuestionList } from './question-list';
import type { Question } from '@/types/homer';

vi.mock('@/lib/api/queries', () => ({
  useAnswerQuestion: () => ({ mutateAsync: vi.fn(), isPending: false, isError: false, error: null }),
  useCancelDispatch: () => ({ mutateAsync: vi.fn(), isPending: false, isError: false, error: null }),
}));

const question = (id: string): Question => ({
  id,
  processId: 7,
  agentName: 'deploy',
  text: `Question ${id}?`,
  options: [],
  status: 'OPEN',
  answer: null,
  createdAt: 1,
  answeredAt: null,
});

describe('QuestionList', () => {
  it('renders the page and says how many of the total it shows when the list is cut', () => {
    render(<QuestionList questions={[question('q-1')]} total={140} />);
    expect(screen.getByText('Question q-1?')).toBeInTheDocument();
    expect(screen.getByText(/Showing the oldest 1 of 140 questions/)).toBeInTheDocument();
  });

  it('adds no note when every question is shown', () => {
    render(<QuestionList questions={[question('q-1')]} total={1} />);
    expect(screen.queryByText(/Showing the oldest/)).not.toBeInTheDocument();
  });
});
