import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';

type LogState = { content: string; ended: boolean };
const logStream = vi.hoisted(() => ({
  byStream: {} as Record<string, LogState>,
  calls: [] as unknown[][],
}));

vi.mock('@/lib/api/streams', () => ({
  useProcessLogStream: (...args: unknown[]) => {
    logStream.calls.push(args);
    return logStream.byStream[args[2] as string];
  },
}));

import { DebugLogPanel } from './debug-log-panel';

function pane(title: string): HTMLElement {
  return screen.getByText(title).parentElement!.parentElement!;
}

describe('DebugLogPanel', () => {
  beforeEach(() => {
    logStream.calls = [];
    logStream.byStream = {
      out: { content: '', ended: false },
      err: { content: '', ended: false },
    };
  });

  it('links to the process detail page', () => {
    render(<DebugLogPanel processId={42} commandIndex={1} onClose={vi.fn()} />);
    expect(screen.getByRole('link', { name: '#42' })).toHaveAttribute('href', '/processes/42');
    expect(screen.getByRole('link', { name: 'Open process detail' })).toHaveAttribute(
      'href',
      '/processes/42',
    );
  });

  it('tails stdout and stderr of the given command, following live', () => {
    render(<DebugLogPanel processId={42} commandIndex={1} onClose={vi.fn()} />);
    expect(logStream.calls).toContainEqual([42, 1, 'out', true]);
    expect(logStream.calls).toContainEqual([42, 1, 'err', true]);
  });

  it('shows a waiting placeholder until output arrives', () => {
    render(<DebugLogPanel processId={1} commandIndex={0} onClose={vi.fn()} />);
    expect(pane('stdout')).toHaveTextContent('Waiting for output...');
    expect(pane('stdout')).not.toHaveTextContent('ended');
  });

  it('marks an ended empty stream as empty', () => {
    logStream.byStream.err = { content: '', ended: true };
    render(<DebugLogPanel processId={1} commandIndex={0} onClose={vi.fn()} />);
    expect(pane('stderr')).toHaveTextContent('ended');
    expect(pane('stderr')).toHaveTextContent('(empty)');
  });

  it('renders streamed content', () => {
    logStream.byStream.out = { content: 'hello\nworld', ended: false };
    render(<DebugLogPanel processId={1} commandIndex={0} onClose={vi.fn()} />);
    expect(pane('stdout')).toHaveTextContent('hello world');
    expect(pane('stdout')).not.toHaveTextContent('Waiting for output...');
  });

  it('calls onClose from the close button', () => {
    const onClose = vi.fn();
    render(<DebugLogPanel processId={1} commandIndex={0} onClose={onClose} />);
    fireEvent.click(screen.getByRole('button', { name: 'Close log panel' }));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
