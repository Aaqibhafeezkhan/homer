import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import AgentEditPage from './page';
import type { AgentInfo } from '@/types/homer';
import { HomerApiError } from '@/lib/api/client';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

// AgentFileEditor pulls in monaco-editor / monaco-yaml as a module-load side
// effect (setupMonaco()), which jsdom can't run, so stub it with a button that
// edits the buffer.
vi.mock('@/components/editor/agent-file-editor', () => ({
  AgentFileEditor: ({ onChange }: { onChange: (value: string) => void }) => (
    <button onClick={() => onChange('name: demo')}>Edit buffer</button>
  ),
}));

const useAgentMock = vi.hoisted(() => vi.fn());
const useAgentFilesMock = vi.hoisted(() => vi.fn());
const useAgentFileMock = vi.hoisted(() => vi.fn());
const useSaveAgentFileMock = vi.hoisted(() => vi.fn());
const useDeleteAgentFileMock = vi.hoisted(() => vi.fn());
const useDebugCommandMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/queries', () => ({
  useAgent: useAgentMock,
  useAgentFiles: useAgentFilesMock,
  useAgentFile: useAgentFileMock,
  useSaveAgentFile: useSaveAgentFileMock,
  useDeleteAgentFile: useDeleteAgentFileMock,
  useDebugCommand: useDebugCommandMock,
}));

vi.mock('@/lib/auth/use-can', () => ({ useCan: vi.fn() }));
import { useCan } from '@/lib/auth/use-can';
import { fakeCapabilities } from '@/lib/auth/capabilities-test-utils';

function mockCan(can: (action: string, name?: string) => boolean, isAdmin = false) {
  vi.mocked(useCan).mockReturnValue(
    fakeCapabilities(isAdmin, { can: can as ReturnType<typeof useCan>['can'] }),
  );
}

// React's `use()` always suspends on a plain Promise, even an already-settled
// one, because JS gives no synchronous way to read a native Promise's state.
// Next.js's real params promise avoids this by pre-attaching the
// {status, value} thenable-cache fields React's `use()` reads synchronously
// (see React's `use(thenable)` implementation) instead of tracking it async.
// Mirror that here so the page renders in one synchronous pass.
function fulfilledParams(name: string): Promise<{ name: string }> {
  const p = Promise.resolve({ name }) as Promise<{ name: string }> & {
    status?: string;
    value?: { name: string };
  };
  p.status = 'fulfilled';
  p.value = { name };
  return p;
}

const writableAgent: AgentInfo = {
  name: 'demo',
  description: null,
  inputs: null,
  queryParams: null,
  scriptCount: 1,
  cron: null,
  writable: true,
};

describe('AgentEditPage capability gating', () => {
  beforeEach(() => {
    useAgentMock.mockReturnValue({ data: writableAgent, isLoading: false });
    useAgentFilesMock.mockReturnValue({ data: { entries: [] }, isLoading: false });
    useAgentFileMock.mockReturnValue({ data: undefined });
    useSaveAgentFileMock.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
    useDeleteAgentFileMock.mockReturnValue({ mutateAsync: vi.fn() });
    useDebugCommandMock.mockReturnValue({ mutateAsync: vi.fn() });
  });

  it('shows the no-permission message and not the editor when the user lacks edit', () => {
    mockCan(() => false);
    render(<AgentEditPage params={fulfilledParams('demo')} />);
    expect(
      screen.getByText(/don.t have permission to edit this agent/i),
    ).toBeInTheDocument();
    expect(screen.queryByText('Select a file to edit')).not.toBeInTheDocument();
    expect(screen.queryByText('Debug')).not.toBeInTheDocument();
  });

  it('shows the editor but not the Debug panel when the user can edit but not debug', () => {
    mockCan((action) => action === 'edit');
    render(<AgentEditPage params={fulfilledParams('demo')} />);
    expect(screen.getByText('Select a file to edit')).toBeInTheDocument();
    expect(screen.queryByText('Debug')).not.toBeInTheDocument();
  });

  it('shows both the editor and the Debug panel when the user can edit and debug', () => {
    mockCan(() => true);
    render(<AgentEditPage params={fulfilledParams('demo')} />);
    expect(screen.getByText('Select a file to edit')).toBeInTheDocument();
    expect(screen.getByText('Debug')).toBeInTheDocument();
  });
});

describe('AgentEditPage save', () => {
  beforeEach(() => {
    mockCan((action) => action === 'edit');
    useAgentMock.mockReturnValue({ data: writableAgent, isLoading: false });
    useAgentFilesMock.mockReturnValue({
      data: { entries: [{ path: 'agent.yaml', size: 1, mtime: 0, isDirectory: false }] },
      isLoading: false,
    });
    useAgentFileMock.mockReturnValue({ data: undefined });
    useDeleteAgentFileMock.mockReturnValue({ mutateAsync: vi.fn() });
    useDebugCommandMock.mockReturnValue({ mutateAsync: vi.fn() });
  });

  async function saveRejectedWith(error: unknown) {
    useSaveAgentFileMock.mockReturnValue({ mutateAsync: vi.fn().mockRejectedValue(error), isPending: false });
    render(<AgentEditPage params={fulfilledParams('demo')} />);
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Edit buffer' }));
    await user.click(screen.getByRole('button', { name: 'Save' }));
  }

  it('lists every schema error of a 422 response', async () => {
    await saveRejectedWith(
      new HomerApiError('Unprocessable', 422, 'Unprocessable Entity', { msg: 'invalid', errors: ['a is required', 'b is unknown'] }),
    );
    expect(await screen.findByText('Schema validation failed:')).toBeInTheDocument();
    expect(screen.getByText('a is required')).toBeInTheDocument();
    expect(screen.getByText('b is unknown')).toBeInTheDocument();
    expect(screen.queryByText('invalid')).not.toBeInTheDocument();
  });

  it('falls back to the message when a 422 lists no errors', async () => {
    await saveRejectedWith(new HomerApiError('Unprocessable', 422, 'Unprocessable Entity', { msg: 'invalid' }));
    expect(await screen.findByText('invalid')).toBeInTheDocument();
  });

  it('shows a generic line for a 422 without a body', async () => {
    await saveRejectedWith(new HomerApiError('Unprocessable', 422));
    expect(await screen.findByText('Schema validation failed')).toBeInTheDocument();
  });

  it('shows the message of a non-422 API failure without a schema list', async () => {
    await saveRejectedWith(new HomerApiError('API request failed: Conflict', 409, 'Conflict', { msg: 'stale' }));
    expect(await screen.findByText('API request failed: Conflict')).toBeInTheDocument();
    expect(screen.queryByText('Schema validation failed:')).not.toBeInTheDocument();
  });

  it('shows a generic line when the save rejects with a non-Error', async () => {
    await saveRejectedWith('boom');
    expect(await screen.findByText('Save failed')).toBeInTheDocument();
  });
});

describe('AgentEditPage delete and new file', () => {
  const save = vi.fn();
  const del = vi.fn();

  beforeEach(() => {
    vi.restoreAllMocks();
    save.mockReset();
    del.mockReset();
    mockCan((action) => action === 'edit');
    useAgentMock.mockReturnValue({ data: writableAgent, isLoading: false });
    useAgentFilesMock.mockReturnValue({
      data: { entries: [{ path: 'notes.md', size: 1, mtime: 0, isDirectory: false }] },
      isLoading: false,
    });
    useAgentFileMock.mockReturnValue({ data: undefined });
    useSaveAgentFileMock.mockReturnValue({ mutateAsync: save, isPending: false });
    useDeleteAgentFileMock.mockReturnValue({ mutateAsync: del });
    useDebugCommandMock.mockReturnValue({ mutateAsync: vi.fn() });
  });

  function renderPage() {
    render(<AgentEditPage params={fulfilledParams('demo')} />);
    return userEvent.setup();
  }

  it('deletes the open file after confirmation and closes it', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    del.mockResolvedValue(undefined);
    const user = renderPage();
    await user.click(screen.getByText('notes.md'));
    expect(screen.getByRole('button', { name: 'Edit buffer' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Delete notes.md' }));
    expect(confirmSpy).toHaveBeenCalledWith('Delete notes.md? This cannot be undone.');
    expect(del).toHaveBeenCalledWith('notes.md');
    expect(await screen.findByText('Select a file to edit')).toBeInTheDocument();
  });

  it('keeps the file when the deletion is not confirmed', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    const user = renderPage();
    await user.click(screen.getByRole('button', { name: 'Delete notes.md' }));
    expect(del).not.toHaveBeenCalled();
  });

  it('shows why a deletion failed', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true);
    del.mockRejectedValueOnce(new Error('locked')).mockRejectedValueOnce('nope');
    const user = renderPage();
    await user.click(screen.getByRole('button', { name: 'Delete notes.md' }));
    expect(await screen.findByText('locked')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Delete notes.md' }));
    expect(await screen.findByText('Delete failed')).toBeInTheDocument();
  });

  it('creates an empty file under the trimmed name and opens it', async () => {
    const promptSpy = vi.spyOn(window, 'prompt').mockReturnValue('  scripts/run.sh  ');
    save.mockResolvedValue({ path: 'scripts/run.sh' });
    const user = renderPage();
    await user.click(screen.getByRole('button', { name: 'New file' }));
    expect(promptSpy).toHaveBeenCalledWith('New file name (relative to agent directory):');
    expect(save).toHaveBeenCalledWith({ path: 'scripts/run.sh', content: '' });
    expect(await screen.findByText('scripts/run.sh')).toBeInTheDocument();
  });

  it('creates nothing when the prompt is cancelled or blank', async () => {
    vi.spyOn(window, 'prompt').mockReturnValueOnce(null).mockReturnValueOnce('   ');
    const user = renderPage();
    await user.click(screen.getByRole('button', { name: 'New file' }));
    await user.click(screen.getByRole('button', { name: 'New file' }));
    expect(save).not.toHaveBeenCalled();
  });

  it('shows why creating a file failed', async () => {
    vi.spyOn(window, 'prompt').mockReturnValue('x.md');
    save.mockRejectedValueOnce(new Error('exists')).mockRejectedValueOnce(42);
    const user = renderPage();
    await user.click(screen.getByRole('button', { name: 'New file' }));
    expect(await screen.findByText('exists')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'New file' }));
    expect(await screen.findByText('Create failed')).toBeInTheDocument();
  });
});

describe('AgentEditPage states, header and error banners', () => {
  const save = vi.fn();

  beforeEach(() => {
    save.mockReset();
    mockCan((action) => action === 'edit');
    useAgentMock.mockReturnValue({ data: writableAgent, isLoading: false });
    useAgentFilesMock.mockReturnValue({
      data: { entries: [{ path: 'agent.yaml', size: 1, mtime: 0, isDirectory: false }] },
      isLoading: false,
    });
    useAgentFileMock.mockReturnValue({ data: undefined });
    useSaveAgentFileMock.mockReturnValue({ mutateAsync: save, isPending: false });
    useDeleteAgentFileMock.mockReturnValue({ mutateAsync: vi.fn() });
    useDebugCommandMock.mockReturnValue({ mutateAsync: vi.fn() });
  });

  function renderPage() {
    render(<AgentEditPage params={fulfilledParams('demo')} />);
    return userEvent.setup();
  }

  it('shows a loading line while the agent loads', () => {
    useAgentMock.mockReturnValue({ data: undefined, isLoading: true });
    renderPage();
    expect(screen.getByText('Loading agent...')).toBeInTheDocument();
  });

  it('shows not-found with a link back when there is no such agent', () => {
    useAgentMock.mockReturnValue({ data: undefined, isLoading: false });
    renderPage();
    expect(screen.getByRole('heading', { name: 'Agent not found' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Back to agents' })).toHaveAttribute('href', '/agents');
  });

  it('disables editing for a read-only agent directory', () => {
    useAgentMock.mockReturnValue({ data: { ...writableAgent, writable: false }, isLoading: false });
    renderPage();
    expect(screen.getByRole('heading', { name: 'demo' })).toBeInTheDocument();
    expect(screen.getByText(/read-only on the server/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Back' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Save' })).not.toBeInTheDocument();
  });

  it('shows a loading line in place of the file tree while the files load', () => {
    useAgentFilesMock.mockReturnValue({ data: undefined, isLoading: true });
    renderPage();
    expect(screen.getByText('Loading files...')).toBeInTheDocument();
  });

  it('marks an edited buffer unsaved and clears the mark once the save succeeds', async () => {
    save.mockResolvedValue({ path: 'agent.yaml' });
    const user = renderPage();
    expect(screen.getByRole('heading', { name: 'Editing demo' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
    expect(screen.queryByText('● Unsaved')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Edit buffer' }));
    expect(screen.getByText('● Unsaved')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Save' }));
    expect(save).toHaveBeenCalledWith({ path: 'agent.yaml', content: 'name: demo' });
    expect(screen.queryByText('● Unsaved')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });

  it('shows Saving... and keeps Save disabled while a save is in flight', () => {
    useSaveAgentFileMock.mockReturnValue({ mutateAsync: save, isPending: true });
    renderPage();
    expect(screen.getByRole('button', { name: 'Saving...' })).toBeDisabled();
  });

  it('dismisses a save error and the schema errors', async () => {
    save
      .mockRejectedValueOnce(new Error('disk full'))
      .mockRejectedValueOnce(new HomerApiError('Unprocessable', 422, 'Unprocessable Entity', { errors: ['a is required'] }));
    const user = renderPage();
    await user.click(screen.getByRole('button', { name: 'Edit buffer' }));

    await user.click(screen.getByRole('button', { name: 'Save' }));
    const saveError = await screen.findByText('disk full');
    await user.click(saveError.parentElement!.querySelector('button')!);
    expect(screen.queryByText('disk full')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Save' }));
    const heading = await screen.findByText('Schema validation failed:');
    await user.click(heading.parentElement!.querySelector('button')!);
    expect(screen.queryByText('Schema validation failed:')).not.toBeInTheDocument();
    expect(screen.queryByText('a is required')).not.toBeInTheDocument();
  });
});
