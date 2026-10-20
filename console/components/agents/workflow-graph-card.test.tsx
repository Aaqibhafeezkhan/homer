import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';

const useAgentLangGraph = vi.fn();
vi.mock('@/lib/api/queries', () => ({
  useAgentLangGraph: (name: string) => useAgentLangGraph(name),
}));

import { WorkflowGraphCard } from './workflow-graph-card';

function expand() {
  fireEvent.click(screen.getByRole('button', { name: /workflow graph/i }));
}

const topology = {
  nodes: [{ id: 'a' }, { id: 'b' }],
  edges: [{ source: 'a', target: 'b', label: null, conditional: false }],
};

describe('WorkflowGraphCard', () => {
  it('renders nothing when there are no graphs', () => {
    useAgentLangGraph.mockReturnValue({ data: [] });
    const { container } = render(<WorkflowGraphCard agentName="a" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('is collapsed by default (no diagrams rendered)', () => {
    useAgentLangGraph.mockReturnValue({
      data: [{ label: 'lg1', module: 'm1', topology }],
    });
    render(<WorkflowGraphCard agentName="a" />);
    expect(screen.queryByRole('img', { name: 'Workflow graph' })).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: /workflow graph/i }),
    ).toHaveAttribute('aria-expanded', 'false');
  });

  it('renders a diagram per graph once expanded', () => {
    useAgentLangGraph.mockReturnValue({
      data: [
        { label: 'lg1', module: 'm1', topology },
        { label: 'lg2', module: 'm2', topology },
      ],
    });
    render(<WorkflowGraphCard agentName="a" />);
    expand();
    expect(screen.getAllByRole('img', { name: 'Workflow graph' })).toHaveLength(2);
  });

  it('shows an error for a failed graph once expanded', () => {
    useAgentLangGraph.mockReturnValue({
      data: [{ label: 'lg1', module: 'm1', error: 'import boom' }],
    });
    render(<WorkflowGraphCard agentName="a" />);
    expand();
    expect(screen.getByText(/import boom/)).toBeInTheDocument();
  });

  it('says the structure is unavailable when an entry has neither topology nor error', () => {
    useAgentLangGraph.mockReturnValue({ data: [{ label: 'lg1', module: 'm1' }] });
    render(<WorkflowGraphCard agentName="a" />);
    expand();
    expect(screen.getByText(/graph structure unavailable/i)).toBeInTheDocument();
  });
});
