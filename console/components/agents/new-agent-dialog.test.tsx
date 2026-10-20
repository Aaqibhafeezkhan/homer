import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HomerApiError } from '@/lib/api/client';
import { axe } from 'vitest-axe';
import { NewAgentDialog, validateAgentName } from './new-agent-dialog';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

const push = vi.hoisted(() => vi.fn());
vi.mock('next/navigation', () => ({
  useRouter: () => ({ push }),
}));

// The create mutation's state; tests override fields, the defaults are an idle mutation.
const createAgent = vi.hoisted(() => ({
  current: {} as { mutateAsync: ReturnType<typeof vi.fn>; isPending: boolean; error: Error | null },
}));
// Stable like the real mutation's reset: the dialog's open effect depends on it.
const reset = vi.hoisted(() => vi.fn());
vi.mock('@/lib/api/queries', () => ({
  useCreateAgent: () => ({ ...createAgent.current, reset }),
}));

beforeEach(() => {
  createAgent.current = { mutateAsync: vi.fn(), isPending: false, error: null };
  push.mockReset();
});

describe('validateAgentName', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('rejects empty names', () => {
    expect(validateAgentName('')).toMatch(/required/i);
    expect(validateAgentName('   ')).toMatch(/required/i);
  });

  it('rejects names with spaces or special chars', () => {
    expect(validateAgentName('bad name')).toMatch(/letters, digits/i);
    expect(validateAgentName('bad!')).toMatch(/letters, digits/i);
    expect(validateAgentName('bad/name')).toMatch(/letters, digits/i);
  });

  it('accepts alphanumeric with dashes and underscores', () => {
    expect(validateAgentName('my-agent')).toBeNull();
    expect(validateAgentName('my_agent_2')).toBeNull();
    expect(validateAgentName('ABC123')).toBeNull();
  });
});

describe('NewAgentDialog', () => {
  function renderDialog() {
    const client = new QueryClient();
    return render(
      <QueryClientProvider client={client}>
        <NewAgentDialog open onOpenChange={vi.fn()} />
      </QueryClientProvider>,
    );
  }

  it('renders with Create button disabled when empty', () => {
    renderDialog();
    expect(screen.getAllByText('Create Agent').length).toBeGreaterThan(0);
    const createButtons = screen
      .getAllByRole('button', { name: /Create/i })
      .filter((b) => b.textContent?.trim().startsWith('Create'));
    expect((createButtons[0] as HTMLButtonElement).disabled).toBe(true);
  });

  it('renders a Cancel button', () => {
    renderDialog();
    const cancels = screen.getAllByRole('button', { name: /Cancel/i });
    expect(cancels.length).toBeGreaterThan(0);
  });

  describe('a11y', () => {
    it('has no violations when open', async () => {
      const { baseElement } = renderDialog();
      // Radix portals dialog content outside the test container,
      // so we run axe against baseElement to catch it.
      expect(await axe(baseElement)).toHaveNoViolations();
    });
  });
});

describe('NewAgentDialog create', () => {
  function renderDialog(onOpenChange = vi.fn()) {
    render(
      <QueryClientProvider client={new QueryClient()}>
        <NewAgentDialog open onOpenChange={onOpenChange} />
      </QueryClientProvider>,
    );
    return onOpenChange;
  }
  const createButton = () => screen.getByRole('button', { name: 'Create' });

  it('refuses an invalid name without calling the backend', async () => {
    renderDialog();
    await userEvent.type(screen.getByLabelText('Name'), 'bad name');
    await userEvent.click(createButton());
    expect(screen.getByText(/letters, digits, dashes, or underscores/)).toBeInTheDocument();
    expect(createAgent.current.mutateAsync).not.toHaveBeenCalled();
  });

  it('clears the validation error once the name is edited', async () => {
    renderDialog();
    await userEvent.type(screen.getByLabelText('Name'), 'bad name');
    await userEvent.click(createButton());
    await userEvent.type(screen.getByLabelText('Name'), 'x');
    expect(screen.queryByText(/letters, digits, dashes, or underscores/)).not.toBeInTheDocument();
  });

  it('closes on Cancel', async () => {
    const onOpenChange = renderDialog();
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('creates the agent, closes and opens its editor', async () => {
    createAgent.current.mutateAsync.mockResolvedValue({ name: 'my agent' });
    const onOpenChange = renderDialog();
    await userEvent.type(screen.getByLabelText('Name'), 'my-agent');
    await userEvent.click(createButton());
    expect(createAgent.current.mutateAsync).toHaveBeenCalledWith('my-agent');
    expect(onOpenChange).toHaveBeenCalledWith(false);
    expect(push).toHaveBeenCalledWith('/agents/my%20agent/edit');
  });

  it('stays open when the backend refuses', async () => {
    createAgent.current.mutateAsync.mockRejectedValue(new Error('nope'));
    const onOpenChange = renderDialog();
    await userEvent.type(screen.getByLabelText('Name'), 'my-agent');
    await userEvent.click(createButton());
    expect(onOpenChange).not.toHaveBeenCalled();
    expect(push).not.toHaveBeenCalled();
  });

  it.each([
    [new HomerApiError('Conflict', 409), 'An agent named "" already exists'],
    [new HomerApiError('Bad Request', 400, 'Bad Request', { msg: 'name reserved' }), 'name reserved'],
    [new HomerApiError('Bad Request', 400, 'Bad Request', null), 'Bad Request'],
    [new HomerApiError('Server Error', 500), 'Server Error'],
    [new Error('offline'), 'offline'],
  ])('shows the create failure %#', (error, message) => {
    createAgent.current.error = error;
    renderDialog();
    expect(screen.getByText(message)).toBeInTheDocument();
  });
});
