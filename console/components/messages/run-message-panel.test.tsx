import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RunMessagePanel } from './run-message-panel';
import { HomerApiError } from '@/lib/api/client';
import type { ProcessStatus, RunMessage } from '@/types/homer';

const { useRunMessagesMock, useSendRunMessageMock } = vi.hoisted(() => ({
  useRunMessagesMock: vi.fn(),
  useSendRunMessageMock: vi.fn(),
}));

vi.mock('@/lib/api/queries', () => ({
  useRunMessages: useRunMessagesMock,
  useSendRunMessage: useSendRunMessageMock,
}));

vi.mock('@/lib/auth/use-can', () => ({ useCan: vi.fn() }));
import { useCan } from '@/lib/auth/use-can';
import { fakeCapabilities } from '@/lib/auth/capabilities-test-utils';

function run(status: ProcessStatus) {
  return { id: 7, status, agentName: 'deploy', owner: 'session:alice' };
}

function message(id: string, overrides: Partial<RunMessage> = {}): RunMessage {
  return {
    id,
    processId: 7,
    text: `Message ${id}`,
    status: 'QUEUED',
    sender: 'alice',
    createdAt: 1_700_000_000,
    deliveredAt: null,
    ...overrides,
  };
}

function sendHook(overrides: Record<string, unknown> = {}) {
  return { mutateAsync: vi.fn(), isPending: false, isError: false, error: null, ...overrides };
}

describe('RunMessagePanel', () => {
  beforeEach(() => {
    useRunMessagesMock.mockReset();
    useSendRunMessageMock.mockReset();
    useSendRunMessageMock.mockReturnValue(sendHook());
    vi.mocked(useCan).mockReturnValue(fakeCapabilities(true));
  });

  it('renders each message with its status and sender, oldest first', () => {
    useRunMessagesMock.mockReturnValue({
      data: {
        messages: [
          message('m-1', { status: 'DELIVERED' }),
          message('m-2', { status: 'QUEUED' }),
          message('m-3', { status: 'UNDELIVERED', sender: null }),
        ],
      },
    });
    render(<RunMessagePanel process={run('WORKING')} />);
    const items = screen.getAllByTestId(/run-message-/);
    expect(items.map((i) => i.getAttribute('data-testid'))).toEqual([
      'run-message-m-1',
      'run-message-m-2',
      'run-message-m-3',
    ]);
    expect(screen.getByLabelText('Message status: Delivered')).toBeInTheDocument();
    expect(screen.getByLabelText('Message status: Queued')).toBeInTheDocument();
    expect(screen.getByLabelText('Message status: Undelivered')).toBeInTheDocument();
    expect(screen.getAllByText('alice')).toHaveLength(2);
  });

  it('renders message text as plain text, not markup', () => {
    useRunMessagesMock.mockReturnValue({ data: { messages: [message('m-1', { text: '<b>hi</b>' })] } });
    render(<RunMessagePanel process={run('WORKING')} />);
    expect(screen.getByText('<b>hi</b>')).toBeInTheDocument();
  });

  it('hides the input for an ended run but keeps its history', () => {
    useRunMessagesMock.mockReturnValue({ data: { messages: [message('m-1', { status: 'UNDELIVERED' })] } });
    render(<RunMessagePanel process={run('FINISHED')} />);
    expect(screen.getByText('Message m-1')).toBeInTheDocument();
    expect(screen.queryByLabelText('Message to the run')).toBeNull();
  });

  it('shows a viewer without control the messages but no input', () => {
    const canKillProcess = vi.fn().mockReturnValue(false);
    vi.mocked(useCan).mockReturnValue(fakeCapabilities(false, { canKillProcess }));
    useRunMessagesMock.mockReturnValue({ data: { messages: [message('m-1')] } });
    render(<RunMessagePanel process={run('WORKING')} />);
    expect(screen.getByText('Message m-1')).toBeInTheDocument();
    expect(screen.queryByLabelText('Message to the run')).toBeNull();
    expect(canKillProcess).toHaveBeenCalledWith(run('WORKING'));
  });

  it('renders nothing for a viewer without control when a running run has no messages', () => {
    vi.mocked(useCan).mockReturnValue(fakeCapabilities(false));
    useRunMessagesMock.mockReturnValue({ data: { messages: [] } });
    const { container } = render(<RunMessagePanel process={run('WORKING')} />);
    expect(container.firstChild).toBeNull();
  });

  it('polls only while the run accepts messages', () => {
    useRunMessagesMock.mockReturnValue({ data: { messages: [message('m-1')] } });
    const { rerender } = render(<RunMessagePanel process={run('WORKING')} />);
    expect(useRunMessagesMock).toHaveBeenLastCalledWith(7, true);
    rerender(<RunMessagePanel process={run('FINISHED')} />);
    expect(useRunMessagesMock).toHaveBeenLastCalledWith(7, false);
  });

  it('renders nothing for an ended run with no messages', () => {
    useRunMessagesMock.mockReturnValue({ data: { messages: [] } });
    const { container } = render(<RunMessagePanel process={run('FINISHED')} />);
    expect(container.firstChild).toBeNull();
  });

  it('shows the input for a run that is accepting messages', () => {
    useRunMessagesMock.mockReturnValue({ data: undefined });
    render(<RunMessagePanel process={run('CREATED')} />);
    expect(screen.getByLabelText('Message to the run')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send' })).toBeDisabled();
  });

  it('sends the trimmed text and clears the box on success', async () => {
    const mutateAsync = vi.fn().mockResolvedValue(message('m-9'));
    useRunMessagesMock.mockReturnValue({ data: { messages: [] } });
    useSendRunMessageMock.mockReturnValue(sendHook({ mutateAsync }));
    render(<RunMessagePanel process={run('WORKING')} />);
    const box = screen.getByLabelText('Message to the run');
    await userEvent.type(box, '  stop now  ');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    await waitFor(() => expect(mutateAsync).toHaveBeenCalledWith('stop now'));
    await waitFor(() => expect(box).toHaveValue(''));
  });

  it('shows the server msg on a 409 and keeps the typed text', async () => {
    const err = new HomerApiError('API request failed: Conflict', 409, 'Conflict', {
      msg: 'Process 7 is no longer accepting messages',
    });
    const mutateAsync = vi.fn().mockRejectedValue(err);
    useRunMessagesMock.mockReturnValue({ data: { messages: [] } });
    useSendRunMessageMock.mockReturnValue(sendHook({ mutateAsync, isError: true, error: err }));
    render(<RunMessagePanel process={run('WORKING')} />);
    const box = screen.getByLabelText('Message to the run');
    await userEvent.type(box, 'hello');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));
    await waitFor(() => expect(mutateAsync).toHaveBeenCalled());
    expect(screen.getByRole('alert')).toHaveTextContent('Process 7 is no longer accepting messages');
    expect(box).toHaveValue('hello');
  });

  it('falls back to the error message when the body has no msg', () => {
    const err = new HomerApiError('API request failed: Too Many Requests', 429, 'Too Many Requests');
    useRunMessagesMock.mockReturnValue({ data: { messages: [] } });
    useSendRunMessageMock.mockReturnValue(sendHook({ isError: true, error: err }));
    render(<RunMessagePanel process={run('WORKING')} />);
    expect(screen.getByRole('alert')).toHaveTextContent('API request failed: Too Many Requests');
  });
});
