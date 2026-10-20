import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ChildRunsCard } from './child-runs-card';
import type { ProcessInfo, ProcessListResponse } from '@/types/homer';

function child(id: number, overrides: Partial<ProcessInfo> = {}): ProcessInfo {
  return {
    id,
    status: 'FINISHED',
    agentName: `child-${id}`,
    history: [],
    executions: [],
    currentCommand: null,
    currentCommandStart: null,
    purgeAt: null,
    ...overrides,
  };
}

function page(processes: ProcessInfo[], total = processes.length): ProcessListResponse {
  return { processes, total, offset: 0, limit: 20 };
}

describe('ChildRunsCard', () => {
  it('renders nothing before the children have loaded', () => {
    const { container } = render(<ChildRunsCard parentProcessId={7} runs={undefined} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('renders nothing for a run without children', () => {
    const { container } = render(<ChildRunsCard parentProcessId={7} runs={page([])} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('links each child with its agent, status and tags', () => {
    render(
      <ChildRunsCard
        parentProcessId={7}
        runs={page([child(8, { tags: ['ticket:ABC-1'] }), child(9, { status: 'FAILED', tags: [] })])}
      />,
    );
    expect(screen.getByText('Child runs')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open child process 8 details' })).toHaveAttribute('href', '/processes/8');
    expect(screen.getByRole('link', { name: 'Open child process 9 details' })).toHaveAttribute('href', '/processes/9');
    expect(screen.getByText('child-8')).toBeInTheDocument();
    expect(screen.getByText('Failed')).toBeInTheDocument();
    expect(screen.getByText('ticket:ABC-1')).toBeInTheDocument();
    expect(screen.queryByText(/View all/)).not.toBeInTheDocument();
  });

  it('links to the full child list when only the first page is shown', () => {
    render(<ChildRunsCard parentProcessId={7} runs={page([child(8)], 25)} />);
    expect(screen.getByRole('link', { name: 'View all 25 child runs' })).toHaveAttribute(
      'href',
      '/processes?parent=7',
    );
  });
});
