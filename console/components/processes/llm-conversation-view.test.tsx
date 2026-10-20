import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { LlmConversationView } from './llm-conversation-view';
import type { LlmEvent } from '@/types/homer';

const start: LlmEvent = { type: 'session_start', sessionId: 's', model: 'gpt-5.2-codex', tools: [] };
const msg: LlmEvent = { type: 'assistant_message', messageId: 'm1', model: null, text: 'thinking out loud', subagent: false };
const call: LlmEvent = { type: 'tool_call', id: 't1', name: 'command_execution', input: { command: 'ls' }, messageId: 't1' };
const res: LlmEvent = { type: 'tool_result', id: 't1', output: 'total 0', isError: false };
const end: LlmEvent = {
  type: 'turn_end',
  status: { type: 'success' },
  usage: { input: 1500, output: 400, cacheRead: 0, cacheWrite: 0, reasoning: 40 },
  costUsd: null,
  model: null,
  durationMs: null,
  durationApiMs: null,
  numTurns: null,
  resultText: 'done',
};

describe('LlmConversationView', () => {
  it('post-mortem: shows model, usage with reasoning, and "not reported" when cost is null', () => {
    render(<LlmConversationView events={[start, msg, call, res, end]} />);
    expect(screen.getByText('gpt-5.2-codex')).toBeInTheDocument();
    expect(screen.getByText('not reported')).toBeInTheDocument();
    expect(screen.getByText('40')).toBeInTheDocument();
  });

  it('shows a muted notice when the event buffer was truncated, not otherwise', () => {
    const { rerender } = render(<LlmConversationView events={[start, msg]} truncated />);
    expect(screen.getByText('Earlier events not shown')).toBeInTheDocument();

    rerender(<LlmConversationView events={[start, msg]} />);
    expect(screen.queryByText('Earlier events not shown')).not.toBeInTheDocument();
  });

  it('post-mortem: shows the cost when reported', () => {
    render(<LlmConversationView events={[start, { ...end, costUsd: 0.0123 } as LlmEvent]} />);
    expect(screen.getByText('$0.0123')).toBeInTheDocument();
  });

  it('post-mortem: a step priced by the spend endpoint shows that cost, though the CLI reported none', () => {
    const accounting = { sessionId: 's', costUsd: 0.008, source: 'session' as const, fallbackReason: null };
    render(<LlmConversationView events={[start, end]} accounting={accounting} />);
    expect(screen.getByText('$0.0080')).toBeInTheDocument();
    expect(screen.queryByText('not reported')).not.toBeInTheDocument();
  });

  it('post-mortem: the resolved cost wins over the CLI figure', () => {
    const accounting = { sessionId: 's', costUsd: 0.02, source: 'session' as const, fallbackReason: null };
    render(<LlmConversationView events={[start, { ...end, costUsd: 0.0123 } as LlmEvent]} accounting={accounting} />);
    expect(screen.getByText('$0.0200')).toBeInTheDocument();
    expect(screen.queryByText('$0.0123')).not.toBeInTheDocument();
  });

  it('post-mortem: a step nobody priced still reads "not reported"', () => {
    const accounting = { sessionId: 's', costUsd: 0, source: 'none' as const, fallbackReason: null };
    render(<LlmConversationView events={[start, end]} accounting={accounting} />);
    expect(screen.getByText('not reported')).toBeInTheDocument();
  });

  it('post-mortem: a turn_end model wins over the session_start model', () => {
    render(<LlmConversationView events={[start, { ...end, model: 'claude-opus-5' } as LlmEvent]} />);
    expect(screen.getByText('claude-opus-5')).toBeInTheDocument();
    expect(screen.queryByText('gpt-5.2-codex')).not.toBeInTheDocument();
  });

  it('live: marks the last tool call without a result as in flight', () => {
    render(<LlmConversationView events={[start, call]} live />);
    expect(screen.getByTestId('inflight-tool')).toBeInTheDocument();
  });

  it('live: counts turns by distinct message id and shows the elapsed timer', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(90_000));
    const second: LlmEvent = { type: 'assistant_message', messageId: 'm2', model: null, text: 'again', subagent: false };
    const sameCallMsg: LlmEvent = { type: 'tool_call', id: 't2', name: 'Bash', input: {}, messageId: 'm2' };
    render(<LlmConversationView events={[start, msg, second, sameCallMsg]} live commandStart={30} />);
    expect(screen.getByText('turn 2')).toBeInTheDocument();
    expect(screen.getByTestId('elapsed-timer')).toHaveTextContent('1:00');
    vi.useRealTimers();
  });

  it('a blank-text assistant_message renders nothing but still counts as a turn', () => {
    const blank: LlmEvent = { type: 'assistant_message', messageId: 'm3', model: null, text: '   ', subagent: false };
    render(<LlmConversationView events={[start, blank]} live />);
    expect(screen.getByText('turn 1')).toBeInTheDocument();
  });

  it('failed turn end shows the reason with a destructive badge', () => {
    render(
      <LlmConversationView
        events={[{ ...end, status: { type: 'failed', reason: 'stream disconnected' } } as LlmEvent]}
      />
    );
    expect(screen.getByText('stream disconnected')).toBeInTheDocument();
    expect(screen.getByText('failed')).toHaveClass('bg-destructive');
  });

  it('stopped turn end shows the reason with a non-destructive badge, distinct from failed', () => {
    render(
      <LlmConversationView
        events={[{ ...end, status: { type: 'stopped', reason: 'error_max_turns' } } as LlmEvent]}
      />
    );
    expect(screen.getByText('error_max_turns')).toBeInTheDocument();
    const badge = screen.getByText('stopped');
    expect(badge).not.toHaveClass('bg-destructive');
  });

  it('colors an errored tool result red', async () => {
    render(<LlmConversationView events={[start, call, { ...res, isError: true } as LlmEvent]} />);
    const user = userEvent.setup();
    await user.click(screen.getByText('command_execution'));
    expect(screen.getByText('total 0')).toHaveClass('text-red-600');
  });
});
