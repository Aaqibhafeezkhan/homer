import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QuestionPanel } from './question-panel';
import type { Question } from '@/types/homer';

const { useQuestionsMock, useAnswerQuestionMock, useCancelDispatchMock } = vi.hoisted(() => ({
  useQuestionsMock: vi.fn(),
  useAnswerQuestionMock: vi.fn(),
  useCancelDispatchMock: vi.fn(),
}));

vi.mock('@/lib/api/queries', () => ({
  useQuestions: useQuestionsMock,
  useAnswerQuestion: useAnswerQuestionMock,
  useCancelDispatch: useCancelDispatchMock,
}));

function question(id: string, overrides: Partial<Question> = {}): Question {
  return {
    id,
    processId: 7,
    agentName: 'deploy',
    text: `Question ${id}?`,
    options: [],
    status: 'OPEN',
    answer: null,
    createdAt: 1_700_000_000,
    answeredAt: null,
    ...overrides,
  };
}

describe('QuestionPanel', () => {
  beforeEach(() => {
    useQuestionsMock.mockReset();
    useAnswerQuestionMock.mockReset();
    useAnswerQuestionMock.mockReturnValue({
      mutateAsync: vi.fn(),
      isPending: false,
      isError: false,
      error: null,
    });
    useCancelDispatchMock.mockReset();
    useCancelDispatchMock.mockReturnValue({
      mutateAsync: vi.fn(),
      isPending: false,
      isError: false,
      error: null,
    });
  });

  it('renders nothing when the process has no questions', () => {
    useQuestionsMock.mockReturnValue({ data: { questions: [] } });
    const { container } = render(<QuestionPanel processId={7} />);
    expect(container.firstChild).toBeNull();
  });

  it('queries questions scoped to the process', () => {
    useQuestionsMock.mockReturnValue({ data: undefined });
    render(<QuestionPanel processId={7} />);
    expect(useQuestionsMock).toHaveBeenCalledWith({ processId: 7 });
  });

  it('says when the process has more questions than the page shows', () => {
    useQuestionsMock.mockReturnValue({ data: { questions: [question('q-1')], total: 2, offset: 0, limit: 1 } });
    render(<QuestionPanel processId={7} />);
    expect(screen.getByText(/Showing the oldest 1 of 2 questions/)).toBeInTheDocument();
  });

  it('renders one item per question', () => {
    useQuestionsMock.mockReturnValue({
      data: { questions: [question('q-1'), question('q-2', { status: 'ANSWERED', answer: 'ok' })] },
    });
    render(<QuestionPanel processId={7} />);
    expect(screen.getByText('Question q-1?')).toBeInTheDocument();
    expect(screen.getByText('Question q-2?')).toBeInTheDocument();
  });
});
