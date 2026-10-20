import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QuestionItem } from './question-item';
import { HomerApiError } from '@/lib/api/client';
import type { Question } from '@/types/homer';

const useAnswerQuestionMock = vi.hoisted(() => vi.fn());
const useCancelDispatchMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/queries', () => ({
  useAnswerQuestion: useAnswerQuestionMock,
  useCancelDispatch: useCancelDispatchMock,
}));

function question(overrides: Partial<Question> = {}): Question {
  return {
    id: 'q-1',
    processId: 7,
    agentName: 'deploy',
    text: 'Deploy **v2** to production?',
    options: [],
    status: 'OPEN',
    answer: null,
    createdAt: 1_700_000_000,
    answeredAt: null,
    ...overrides,
  };
}

interface MutationState {
  mutateAsync: ReturnType<typeof vi.fn>;
  isPending: boolean;
  isError: boolean;
  error: unknown;
}

function setMutation(overrides: Partial<MutationState> = {}): MutationState {
  const state: MutationState = {
    mutateAsync: vi.fn().mockResolvedValue(undefined),
    isPending: false,
    isError: false,
    error: null,
    ...overrides,
  };
  useAnswerQuestionMock.mockReturnValue(state);
  return state;
}

function setCancelMutation(overrides: Partial<MutationState> = {}): MutationState {
  const state: MutationState = {
    mutateAsync: vi.fn().mockResolvedValue(undefined),
    isPending: false,
    isError: false,
    error: null,
    ...overrides,
  };
  useCancelDispatchMock.mockReturnValue(state);
  return state;
}

describe('QuestionItem', () => {
  beforeEach(() => {
    useAnswerQuestionMock.mockReset();
    useCancelDispatchMock.mockReset();
    setCancelMutation();
  });

  it('renders the question text as Markdown', () => {
    setMutation();
    render(<QuestionItem question={question()} />);
    expect(screen.getByText('v2').tagName).toBe('STRONG');
  });

  it('escapes raw HTML in question text instead of rendering it', () => {
    setMutation();
    const { container } = render(
      <QuestionItem question={question({ text: 'Click <img src=x onerror=alert(1)> here' })} />
    );
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText(/Click/)).toBeInTheDocument();
  });

  it('does not render a markdown image (no beacon), showing its alt text instead', () => {
    setMutation();
    const { container } = render(
      <QuestionItem question={question({ text: 'Look ![the alt text](https://attacker.example/beacon.png)' })} />
    );
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('the alt text')).toBeInTheDocument();
  });

  it('submits a predefined option on click', async () => {
    const mutation = setMutation();
    render(<QuestionItem question={question({ options: ['yes', 'no'] })} />);

    await userEvent.click(screen.getByRole('button', { name: 'yes' }));

    expect(mutation.mutateAsync).toHaveBeenCalledWith({ id: 'q-1', answer: 'yes' });
  });

  it('submits a typed custom answer', async () => {
    const mutation = setMutation();
    render(<QuestionItem question={question()} />);

    await userEvent.type(screen.getByLabelText('Custom answer'), 'use the blue one');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));

    expect(mutation.mutateAsync).toHaveBeenCalledWith({ id: 'q-1', answer: 'use the blue one' });
  });

  it('shows the answer for an answered question without input controls', () => {
    setMutation();
    render(
      <QuestionItem
        question={question({ status: 'ANSWERED', answer: 'go ahead', answeredAt: 1_700_000_100 })}
      />
    );
    expect(screen.getByText('go ahead')).toBeInTheDocument();
    expect(screen.queryByLabelText('Custom answer')).toBeNull();
  });

  it('greys out an expired question and offers no controls', () => {
    setMutation();
    render(<QuestionItem question={question({ status: 'EXPIRED' })} />);
    expect(screen.getByTestId('question-q-1').className).toContain('opacity-60');
    expect(screen.queryByLabelText('Custom answer')).toBeNull();
    expect(screen.getByText(/run ended before/i)).toBeInTheDocument();
  });

  it('surfaces a 409 conflict inline', () => {
    setMutation({
      isError: true,
      error: new HomerApiError('conflict', 409, 'Conflict'),
    });
    render(<QuestionItem question={question()} />);
    expect(screen.getByRole('alert')).toHaveTextContent(/no longer open/i);
  });

  it('shows dispatch annotation for an open question with a pending dispatch', () => {
    setMutation();
    render(
      <QuestionItem
        question={question({
          status: 'OPEN',
          dispatch: { id: 3, agentName: 'handler', status: 'PENDING' },
        })}
      />
    );
    expect(screen.getByText(/Answering will start/i)).toBeInTheDocument();
    expect(screen.getByText('handler')).toBeInTheDocument();
  });

  it('shows a Cancel question button for an open dispatch-bound question', () => {
    setMutation();
    render(
      <QuestionItem
        question={question({
          status: 'OPEN',
          dispatch: { id: 3, agentName: 'handler', status: 'PENDING' },
        })}
      />
    );
    expect(screen.getByRole('button', { name: 'Cancel question' })).toBeInTheDocument();
  });

  it('does not show a Cancel button for a standalone open question', () => {
    setMutation();
    render(<QuestionItem question={question({ status: 'OPEN' })} />);
    expect(screen.queryByRole('button', { name: 'Cancel question' })).toBeNull();
  });

  it('does not show a Cancel button for an answered dispatch-bound question', () => {
    setMutation();
    render(
      <QuestionItem
        question={question({
          status: 'ANSWERED',
          answer: 'yes',
          dispatch: { id: 3, agentName: 'handler', status: 'DISPATCHED', dispatchedProcessId: 9 },
        })}
      />
    );
    expect(screen.queryByRole('button', { name: 'Cancel question' })).toBeNull();
  });

  it('cancels the dispatch by id after confirming the dialog', async () => {
    setMutation();
    const cancel = setCancelMutation();
    render(
      <QuestionItem
        question={question({
          status: 'OPEN',
          dispatch: { id: 3, agentName: 'handler', status: 'PENDING' },
        })}
      />
    );

    await userEvent.click(screen.getByRole('button', { name: 'Cancel question' }));
    await userEvent.click(screen.getByRole('button', { name: 'Yes, cancel' }));

    expect(cancel.mutateAsync).toHaveBeenCalledWith(3);
  });

  it('surfaces a 409 from cancel inline', () => {
    setMutation();
    setCancelMutation({
      isError: true,
      error: new HomerApiError('conflict', 409, 'Conflict'),
    });
    render(
      <QuestionItem
        question={question({
          status: 'OPEN',
          dispatch: { id: 3, agentName: 'handler', status: 'PENDING' },
        })}
      />
    );
    expect(screen.getByText(/can no longer be cancelled/i)).toBeInTheDocument();
  });
});
