import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import type { AgentInfo } from '@/types/homer';

const mutation = vi.hoisted(() => ({
  mutateAsync: vi.fn(),
  reset: vi.fn(),
  isPending: false,
  error: null as Error | null,
}));

vi.mock('@/lib/api/queries', () => ({ useDebugCommand: () => mutation }));

import { DebugRunDialog } from './debug-run-dialog';

const agent: AgentInfo = {
  name: 'a',
  description: null,
  inputs: [
    {
      paramName: 'id',
      envName: 'ID',
      src: 'query',
      type: 'STRING',
      required: true,
      transformer: { type: 'pass' },
    },
  ],
  queryParams: null,
  scriptCount: 2,
  cron: null,
  writable: true,
};

function renderDialog(props: Partial<React.ComponentProps<typeof DebugRunDialog>> = {}) {
  const onStarted = vi.fn();
  const onOpenChange = vi.fn();
  const utils = render(
    <DebugRunDialog
      open
      onOpenChange={onOpenChange}
      agentName="a"
      agent={agent}
      commandIndex={1}
      onStarted={onStarted}
      {...props}
    />,
  );
  return { ...utils, onStarted, onOpenChange };
}

describe('DebugRunDialog', () => {
  beforeEach(() => {
    mutation.mutateAsync.mockReset();
    mutation.reset.mockReset();
    mutation.isPending = false;
    mutation.error = null;
  });

  it('prefills one empty row per declared input and resets the mutation on open', () => {
    renderDialog();
    expect(screen.getByText('Debug Command #2')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('param')).toHaveValue('id');
    expect(screen.queryByPlaceholderText('NAME')).not.toBeInTheDocument();
    expect(screen.getByText(/→ ID/)).toHaveTextContent('Direct pass-through');
    expect(mutation.reset).toHaveBeenCalled();
  });

  it('prefills the recalled env and params instead of the declared inputs', () => {
    renderDialog({
      initialEnv: [{ key: 'FOO', value: 'bar' }],
      initialParams: [{ key: 'other', value: '1' }],
    });
    expect(screen.getByPlaceholderText('NAME')).toHaveValue('FOO');
    expect(screen.getByPlaceholderText('param')).toHaveValue('other');
    expect(screen.queryByText(/→ ID/)).not.toBeInTheDocument();
  });

  it('does not reset the form while closed', () => {
    renderDialog({ open: false });
    expect(mutation.reset).not.toHaveBeenCalled();
  });

  it('runs the command with the edited rows and reports the new process', async () => {
    mutation.mutateAsync.mockResolvedValue({ processId: 7 });
    const { onStarted } = renderDialog();
    fireEvent.change(screen.getAllByPlaceholderText('value')[0], { target: { value: '5' } });
    fireEvent.click(screen.getByRole('button', { name: /Add env var/ }));
    fireEvent.change(screen.getByPlaceholderText('NAME'), { target: { value: 'X' } });
    fireEvent.click(screen.getByRole('button', { name: 'Run' }));
    await waitFor(() => expect(onStarted).toHaveBeenCalled());
    expect(mutation.mutateAsync).toHaveBeenCalledWith({
      commandIndex: 1,
      env: { X: '' },
      queryParams: { id: '5' },
    });
    expect(onStarted).toHaveBeenCalledWith(
      7,
      [{ key: 'X', value: '' }],
      [{ key: 'id', value: '5' }],
    );
  });

  it('edits only the row typed into', () => {
    renderDialog();
    fireEvent.click(screen.getByRole('button', { name: /Add query param/ }));
    fireEvent.change(screen.getAllByPlaceholderText('param')[1], { target: { value: 'extra' } });
    expect(screen.getAllByPlaceholderText('param').map((input) => (input as HTMLInputElement).value)).toEqual([
      'id',
      'extra',
    ]);
  });

  it('removes a row', () => {
    renderDialog();
    fireEvent.click(screen.getByRole('button', { name: 'Remove row' }));
    expect(screen.queryByPlaceholderText('param')).not.toBeInTheDocument();
  });

  it('swallows a failed run and shows the mutation error', async () => {
    mutation.mutateAsync.mockRejectedValue(new Error('nope'));
    mutation.error = new Error('locked');
    const { onStarted } = renderDialog();
    fireEvent.click(screen.getByRole('button', { name: 'Run' }));
    await waitFor(() => expect(mutation.mutateAsync).toHaveBeenCalled());
    expect(onStarted).not.toHaveBeenCalled();
    expect(screen.getByText('locked')).toBeInTheDocument();
  });

  it('disables the run button while starting', () => {
    mutation.isPending = true;
    renderDialog();
    expect(screen.getByRole('button', { name: /Starting/ })).toBeDisabled();
  });

  it('closes from the cancel button', () => {
    const { onOpenChange } = renderDialog();
    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});
