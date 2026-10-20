import { describe, it, expect, vi, afterEach } from 'vitest';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RunAgentDialog } from './run-agent-dialog';
import * as queries from '@/lib/api/queries';
import * as curl from '@/lib/utils/curl';
import type { AgentInfo, AgentInput } from '@/types/homer';

vi.mock('next/navigation', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

const agent: AgentInfo = {
  name: 'mix',
  description: null,
  inputs: [
    { paramName: 'id', envName: 'ID', src: 'query', type: 'STRING', required: true, transformer: { type: 'pass' } },
    { paramName: 'user', envName: 'USER', src: 'body', type: 'STRING', required: true, transformer: { type: 'pass' } },
    { paramName: 'X-Trace-Id', envName: 'TRACE', src: 'header', type: 'ANY', required: false, transformer: { type: 'pass' } },
  ],
  queryParams: null,
  scriptCount: 1,
  cron: null,
};

function mockExecuteAgent(mutateAsync: ReturnType<typeof vi.fn>) {
  vi.spyOn(queries, 'useExecuteAgent').mockReturnValue(
    ({ mutateAsync, isPending: false, error: null }) as unknown as ReturnType<typeof queries.useExecuteAgent>
  );
}

describe('RunAgentDialog with mixed sources', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('groups inputs by source and submits structured payload', async () => {
    const mutateAsync = vi.fn().mockResolvedValue({ processId: 1 });
    mockExecuteAgent(mutateAsync);
    render(<RunAgentDialog open onOpenChange={() => {}} agent={agent} />);

    expect(screen.getByText(/Query Parameters/i)).toBeInTheDocument();
    expect(screen.getByText(/Body \(JSON\)/i)).toBeInTheDocument();
    expect(screen.getByText(/Headers/i)).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('id'), { target: { value: '42' } });
    fireEvent.change(screen.getByLabelText('user'), { target: { value: 'alice' } });
    fireEvent.change(screen.getByLabelText('X-Trace-Id'), { target: { value: 'abc' } });

    fireEvent.click(screen.getByRole('button', { name: /run/i }));

    await waitFor(() => {
      expect(mutateAsync).toHaveBeenCalledWith({
        agentName: 'mix',
        opts: {
          queryParams: { id: '42' },
          body: { user: 'alice' },
          headers: { 'X-Trace-Id': 'abc' },
        },
      });
    });
  });

  it('falls back to queryParams alias when inputs is null', async () => {
    const mutateAsync = vi.fn().mockResolvedValue({ processId: 1 });
    mockExecuteAgent(mutateAsync);
    const legacyAgent: AgentInfo = {
      ...agent,
      inputs: null,
      queryParams: [
        { paramName: 'id', envName: 'ID', src: 'query', type: 'STRING', required: true, transformer: { type: 'pass' } },
      ],
    };
    render(<RunAgentDialog open onOpenChange={() => {}} agent={legacyAgent} />);

    expect(screen.getByText(/Query Parameters/i)).toBeInTheDocument();
    expect(screen.getByLabelText('id')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('id'), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: /run/i }));

    await waitFor(() => {
      expect(mutateAsync).toHaveBeenCalledWith({
        agentName: 'mix',
        opts: {
          queryParams: { id: '42' },
          body: undefined,
          headers: undefined,
        },
      });
    });
  });

  it('shows the manual-body note in the curl tab when the agent has no body inputs', async () => {
    const user = userEvent.setup();
    mockExecuteAgent(vi.fn());
    const qOnly: AgentInfo = {
      ...agent,
      inputs: [
        { paramName: 'id', envName: 'ID', src: 'query', type: 'STRING', required: true, transformer: { type: 'pass' } },
      ],
    };
    render(<RunAgentDialog open onOpenChange={() => {}} agent={qOnly} />);

    await user.click(screen.getByRole('tab', { name: /export curl/i }));

    expect(screen.getByText(/carries no JSON body/i)).toBeInTheDocument();
  });

  it('hides the manual-body note in the curl tab when the agent has body inputs', async () => {
    const user = userEvent.setup();
    mockExecuteAgent(vi.fn());
    render(<RunAgentDialog open onOpenChange={() => {}} agent={agent} />);

    await user.click(screen.getByRole('tab', { name: /export curl/i }));

    expect(screen.queryByText(/carries no JSON body/i)).not.toBeInTheDocument();
  });

  it('exports the auth placeholder and never a raw token in the curl command', async () => {
    const user = userEvent.setup();
    mockExecuteAgent(vi.fn());
    render(<RunAgentDialog open onOpenChange={() => {}} agent={agent} />);

    await user.click(screen.getByRole('tab', { name: /export curl/i }));

    const cmd = screen.getByText(/curl -X POST/i).textContent ?? '';
    expect(cmd).toContain('Authorization: Bearer $HOMER_AUTH_TOKEN');
    expect(cmd).not.toMatch(/Bearer\s+[A-Za-z0-9._-]{8,}/);
  });

  it('emits body param values entered by the user in the curl -d flag', async () => {
    const user = userEvent.setup();
    mockExecuteAgent(vi.fn());
    render(<RunAgentDialog open onOpenChange={() => {}} agent={agent} />);

    // 'user' is the fixture's body param; enter it before switching tabs
    // (Radix unmounts the inactive tab, but the reducer keeps the value).
    fireEvent.change(screen.getByLabelText('user'), { target: { value: 'alice' } });
    await user.click(screen.getByRole('tab', { name: /export curl/i }));

    const cmd = screen.getByText(/curl -X POST/i).textContent ?? '';
    expect(cmd).toContain("-H 'Content-Type: application/json'");
    expect(cmd).toContain('"user":"alice"');
  });

  it('omits the -d flag in the curl command when body params are left blank', async () => {
    const user = userEvent.setup();
    mockExecuteAgent(vi.fn());
    render(<RunAgentDialog open onOpenChange={() => {}} agent={agent} />);

    await user.click(screen.getByRole('tab', { name: /export curl/i }));

    const cmd = screen.getByText(/curl -X POST/i).textContent ?? '';
    expect(cmd).not.toContain('-d ');
    expect(cmd).not.toContain('Content-Type: application/json');
  });

  it('omits body/headers buckets when agent has only query inputs', async () => {
    const mutateAsync = vi.fn().mockResolvedValue({ processId: 1 });
    mockExecuteAgent(mutateAsync);
    const qOnly: AgentInfo = {
      ...agent,
      inputs: [
        { paramName: 'id', envName: 'ID', src: 'query', type: 'STRING', required: true, transformer: { type: 'pass' } },
      ],
    };
    render(<RunAgentDialog open onOpenChange={() => {}} agent={qOnly} />);
    expect(screen.queryByText(/Body \(JSON\)/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Headers/i)).not.toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('id'), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: /run/i }));

    await waitFor(() => {
      expect(mutateAsync).toHaveBeenCalledWith({
        agentName: 'mix',
        opts: {
          queryParams: { id: '42' },
          body: undefined,
          headers: undefined,
        },
      });
    });
  });
});

// Mirrors the typed-input shape of app/e2e/fixtures/agents/inputs/agent.yaml.
function typedAgent(...inputs: AgentInput[]): AgentInfo {
  return {
    name: 'inputs',
    description: null,
    inputs,
    queryParams: null,
    scriptCount: 1,
    cron: null,
  };
}

describe('RunAgentDialog input list and typed constraints', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('bounds the scroll viewport, not the ScrollArea root', () => {
    mockExecuteAgent(vi.fn());
    render(<RunAgentDialog open onOpenChange={() => {}} agent={agent} />);

    // jsdom has no layout: assert the bound targets the Radix viewport, since
    // a bare `max-h` on the root clips instead of scrolling.
    const viewport = document.querySelector('[data-radix-scroll-area-viewport]');
    expect(viewport).not.toBeNull();
    const root = viewport!.parentElement as HTMLElement;
    expect(root.className).toContain(
      '[&>[data-radix-scroll-area-viewport]]:max-h-[400px]'
    );
    expect(root.className).not.toMatch(/(^|\s)max-h-\[400px\](\s|$)/);
  });

  it('renders an input’s allowed values', () => {
    mockExecuteAgent(vi.fn());
    render(
      <RunAgentDialog
        open
        onOpenChange={() => {}}
        agent={typedAgent({
          paramName: 'environment',
          envName: 'ENV_NAME',
          src: 'query',
          type: 'string',
          required: false,
          transformer: { type: 'pass' },
          values: ['dev', 'staging', 'prod'],
        })}
      />
    );

    expect(screen.getByText('Allowed: dev, staging, prod')).toBeInTheDocument();
  });

  it('renders an input’s default', () => {
    mockExecuteAgent(vi.fn());
    render(
      <RunAgentDialog
        open
        onOpenChange={() => {}}
        agent={typedAgent({
          paramName: 'count',
          envName: 'COUNT',
          src: 'query',
          type: 'integer',
          required: false,
          transformer: { type: 'pass' },
          default: '10',
        })}
      />
    );

    expect(screen.getByText('Default: 10')).toBeInTheDocument();
  });

  it('never renders a secret input’s default value', () => {
    mockExecuteAgent(vi.fn());
    render(
      <RunAgentDialog
        open
        onOpenChange={() => {}}
        agent={typedAgent({
          paramName: 'token',
          envName: 'SECRET_TOKEN',
          src: 'query',
          type: 'ANY',
          required: false,
          transformer: { type: 'pass' },
          secret: true,
          default: 'super-secret',
        })}
      />
    );

    expect(screen.queryByText(/Default:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/super-secret/)).not.toBeInTheDocument();
  });

  // A secret input's allowed set must not render even when the payload carries it.
  it('never renders a secret input’s allowed values', () => {
    mockExecuteAgent(vi.fn());
    render(
      <RunAgentDialog
        open
        onOpenChange={() => {}}
        agent={typedAgent({
          paramName: 'token',
          envName: 'SECRET_TOKEN',
          src: 'query',
          type: 'ANY',
          required: false,
          transformer: { type: 'pass' },
          secret: true,
          values: ['abc', 'def'],
        })}
      />
    );

    expect(screen.queryByText(/Allowed:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/abc, def/)).not.toBeInTheDocument();
  });

  it('renders no constraint line for an input declaring neither field', () => {
    mockExecuteAgent(vi.fn());
    render(
      <RunAgentDialog
        open
        onOpenChange={() => {}}
        agent={typedAgent({
          paramName: 'flag',
          envName: 'FLAG',
          src: 'query',
          type: 'boolean',
          required: false,
          transformer: { type: 'pass' },
        })}
      />
    );

    expect(screen.queryByText(/Default:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Allowed:/)).not.toBeInTheDocument();
  });

  // getAgentInputs falls back to the deprecated queryParams alias verbatim.
  it('renders the alias path’s default, values and secret redaction like the canonical one', () => {
    mockExecuteAgent(vi.fn());
    render(
      <RunAgentDialog
        open
        onOpenChange={() => {}}
        agent={{
          name: 'inputs',
          description: null,
          inputs: null,
          queryParams: [
            {
              paramName: 'environment',
              envName: 'ENV_NAME',
              src: 'query',
              type: 'string',
              required: false,
              transformer: { type: 'pass' },
              values: ['dev', 'staging', 'prod'],
            },
            {
              paramName: 'count',
              envName: 'COUNT',
              src: 'query',
              type: 'integer',
              required: false,
              transformer: { type: 'pass' },
              default: '10',
            },
            {
              paramName: 'token',
              envName: 'SECRET_TOKEN',
              src: 'query',
              type: 'ANY',
              required: false,
              transformer: { type: 'pass' },
              secret: true,
              default: 'super-secret',
            },
          ],
          scriptCount: 1,
          cron: null,
        }}
      />
    );

    expect(screen.getByText('Allowed: dev, staging, prod')).toBeInTheDocument();
    expect(screen.getByText('Default: 10')).toBeInTheDocument();
    expect(screen.queryByText(/Default: super-secret/)).not.toBeInTheDocument();
    expect(screen.queryByText(/super-secret/)).not.toBeInTheDocument();
  });
});

describe('RunAgentDialog actions', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  const queryOnly: AgentInfo = {
    ...agent,
    inputs: [
      { paramName: 'id', envName: 'ID', src: 'query', type: 'STRING', required: true, transformer: { type: 'pass' } },
    ],
  };

  it('copies the curl command and resets the confirmation after two seconds', async () => {
    vi.useFakeTimers();
    mockExecuteAgent(vi.fn());
    const copy = vi.spyOn(curl, 'copyToClipboard').mockResolvedValue(true);
    render(<RunAgentDialog open onOpenChange={() => {}} agent={queryOnly} />);

    fireEvent.mouseDown(screen.getByRole('tab', { name: /export curl/i }));
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /copy to clipboard/i }));
    });

    expect(copy).toHaveBeenCalledWith(expect.stringContaining('curl -X POST'));
    expect(screen.getByRole('button', { name: /copied!/i })).toBeInTheDocument();
    act(() => {
      vi.advanceTimersByTime(2000);
    });
    expect(screen.getByRole('button', { name: /copy to clipboard/i })).toBeInTheDocument();
  });

  it('keeps the copy label when the clipboard refuses', async () => {
    mockExecuteAgent(vi.fn());
    vi.spyOn(curl, 'copyToClipboard').mockResolvedValue(false);
    const user = userEvent.setup();
    render(<RunAgentDialog open onOpenChange={() => {}} agent={queryOnly} />);

    await user.click(screen.getByRole('tab', { name: /export curl/i }));
    await user.click(screen.getByRole('button', { name: /copy to clipboard/i }));

    expect(screen.queryByRole('button', { name: /copied!/i })).not.toBeInTheDocument();
  });

  it('logs a failed run and stays open', async () => {
    const error = new Error('boom');
    mockExecuteAgent(vi.fn().mockRejectedValue(error));
    const log = vi.spyOn(console, 'error').mockImplementation(() => {});
    const onOpenChange = vi.fn();
    render(<RunAgentDialog open onOpenChange={onOpenChange} agent={queryOnly} />);

    fireEvent.change(screen.getByLabelText('id'), { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: /run/i }));

    await waitFor(() => expect(log).toHaveBeenCalledWith('Failed to execute agent:', error));
    expect(onOpenChange).not.toHaveBeenCalled();
  });

  it('closes on Cancel', () => {
    mockExecuteAgent(vi.fn());
    const onOpenChange = vi.fn();
    render(<RunAgentDialog open onOpenChange={onOpenChange} agent={queryOnly} />);

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});
