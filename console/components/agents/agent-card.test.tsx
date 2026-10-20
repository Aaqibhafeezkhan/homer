import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { AgentCard } from './agent-card';
import type { AgentInfo } from '@/types/homer';

vi.mock('@/lib/auth/use-can', () => ({ useCan: vi.fn() }));
import { useCan } from '@/lib/auth/use-can';
import { fakeCapabilities } from '@/lib/auth/capabilities-test-utils';

function mockCan(can: (action: string, name?: string) => boolean, isAdmin = false) {
  vi.mocked(useCan).mockReturnValue(
    fakeCapabilities(isAdmin, { can: can as ReturnType<typeof useCan>['can'] }),
  );
}

const baseAgent: AgentInfo = {
  name: 'demo',
  description: null,
  inputs: null,
  queryParams: null,
  scriptCount: 1,
  cron: null,
};

describe('AgentCard', () => {
  beforeEach(() => {
    mockCan(() => true);
  });

  it('always shows Run button', () => {
    render(<AgentCard agent={baseAgent} onRunClick={vi.fn()} />);
    expect(
      screen.getByRole('button', { name: /run agent demo/i }),
    ).toBeInTheDocument();
  });

  it('renders Edit link when agent is writable and editable', () => {
    render(
      <AgentCard agent={{ ...baseAgent, writable: true }} onRunClick={vi.fn()} />,
    );
    const editLink = screen.getByRole('link', { name: /edit agent demo/i });
    expect(editLink).toBeInTheDocument();
    expect(editLink).toHaveAttribute('href', '/agents/demo/edit');
  });

  it('hides Edit link when agent is not writable', () => {
    render(
      <AgentCard agent={{ ...baseAgent, writable: false }} onRunClick={vi.fn()} />,
    );
    expect(
      screen.queryByRole('link', { name: /edit agent/i }),
    ).not.toBeInTheDocument();
  });

  it('hides Edit link when writable flag is absent (read-only by default)', () => {
    render(<AgentCard agent={baseAgent} onRunClick={vi.fn()} />);
    expect(
      screen.queryByRole('link', { name: /edit agent/i }),
    ).not.toBeInTheDocument();
  });

  it('hides Edit link when the user lacks edit on the agent, even if writable', () => {
    mockCan(() => false);
    render(
      <AgentCard agent={{ ...baseAgent, writable: true }} onRunClick={vi.fn()} />,
    );
    expect(
      screen.queryByLabelText('Edit agent demo'),
    ).not.toBeInTheDocument();
  });

  it('shows Edit link when editable and permitted', () => {
    mockCan((action) => action === 'edit');
    render(
      <AgentCard agent={{ ...baseAgent, writable: true }} onRunClick={vi.fn()} />,
    );
    expect(screen.getByLabelText('Edit agent demo')).toBeInTheDocument();
  });

  it('URL-encodes agent names with special characters', () => {
    render(
      <AgentCard
        agent={{ ...baseAgent, name: 'my agent', writable: true }}
        onRunClick={vi.fn()}
      />,
    );
    const editLink = screen.getByRole('link', { name: /edit agent my agent/i });
    expect(editLink).toHaveAttribute('href', '/agents/my%20agent/edit');
  });

  describe('a11y', () => {
    it('has no violations for a basic read-only agent', async () => {
      const { container } = render(
        <AgentCard agent={baseAgent} onRunClick={vi.fn()} />,
      );
      expect(await axe(container)).toHaveNoViolations();
    });

    it('has no violations for a writable scheduled agent with parameters', async () => {
      const agent: AgentInfo = {
        ...baseAgent,
        description: 'Demo agent description',
        writable: true,
        cron: {
          expression: '*/5 * * * *',
          timezone: 'UTC',
          nextRunAt: 1_700_000_000,
          lastRunAt: 1_699_999_700,
          lastRunProcessId: 7,
        },
        inputs: [
          {
            paramName: 'region',
            envName: 'REGION',
            src: 'query',
            type: 'STRING',
            required: true,
            transformer: { type: 'pass' },
          },
        ],
      };
      const { container } = render(
        <AgentCard agent={agent} onRunClick={vi.fn()} />,
      );
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});
