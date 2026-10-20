import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { WorkflowGraph } from './workflow-graph';
import type { LangGraphTopology } from '@/types/homer';

const topology: LangGraphTopology = {
  nodes: [{ id: '__start__' }, { id: 'plan' }, { id: 'review' }, { id: '__end__' }],
  edges: [
    { source: '__start__', target: 'plan', label: null, conditional: false },
    { source: 'plan', target: 'review', label: null, conditional: false },
    { source: 'review', target: '__end__', label: 'ok', conditional: true },
  ],
};

// A node's <title> (full id) duplicates its <text> label, so look it up by
// iterating data-node-id groups rather than relying on CSS.escape (jsdom may
// not implement it) or a single-match query.
const nodeGroup = (container: HTMLElement, id: string): SVGGElement => {
  const groups = container.querySelectorAll('[data-node-id]');
  for (const g of Array.from(groups)) {
    if (g.getAttribute('data-node-id') === id) return g as SVGGElement;
  }
  throw new Error(`no node group for ${id}`);
};

describe('WorkflowGraph', () => {
  it('draws every node and edge label as text', () => {
    render(<WorkflowGraph topology={topology} />);
    for (const text of ['start', 'plan', 'review', 'end', 'ok']) {
      expect(screen.getAllByText(text).length).toBeGreaterThanOrEqual(1);
    }
  });

  it('renders hostile labels as text, never as markup', () => {
    const evil = '<img src=x onerror=alert(1)>';
    const { container } = render(
      <WorkflowGraph
        topology={{ nodes: [{ id: evil }, { id: '</svg><script>x</script>' }], edges: [{ source: evil, target: evil, label: '<b>x</b>', conditional: true }] }}
      />,
    );
    expect(container.querySelector('img, script, b')).toBeNull();
    expect(screen.getAllByText(evil).length).toBeGreaterThanOrEqual(1);
  });

  it('applies the caller’s node classes to plain nodes only', () => {
    const { container } = render(
      <WorkflowGraph topology={topology} nodeClassName={(id) => (id === 'plan' ? 'fill-green-500/15' : undefined)} />,
    );
    expect(nodeGroup(container, 'plan').querySelector('rect')!.getAttribute('class')).toContain('fill-green-500/15');
    expect(nodeGroup(container, 'review').querySelector('rect')!.getAttribute('class')).toContain('fill-card');
  });

  it('dashes conditional edges', () => {
    const { container } = render(<WorkflowGraph topology={topology} />);
    const dashed = container.querySelectorAll('path[data-conditional="true"]');
    expect(dashed).toHaveLength(1);
  });

  it('explains solid vs dashed edges in a legend', () => {
    render(<WorkflowGraph topology={topology} />);
    const legend = screen.getByLabelText('edge legend');
    expect(legend.textContent).toContain('fixed edge');
    expect(legend.textContent).toContain('conditional edge');
  });

  it('shows no edge legend when the graph cannot be drawn', () => {
    const nodes = Array.from({ length: 201 }, (_, i) => ({ id: `n${i}` }));
    render(<WorkflowGraph topology={{ nodes, edges: [] }} />);
    expect(screen.queryByLabelText('edge legend')).toBeNull();
  });

  it('marks a taken edge with data-traversed and leaves the rest untouched', () => {
    const { container } = render(
      <WorkflowGraph topology={topology} traversed={[{ source: 'plan', target: 'review' }]} />,
    );
    expect(container.querySelectorAll('path[data-traversed="true"]')).toHaveLength(1);
    expect(container.querySelectorAll('path:not([data-traversed])').length).toBeGreaterThan(0);
  });

  it('marks a taken self-loop', () => {
    const selfLoop: LangGraphTopology = {
      nodes: [{ id: 'a' }, { id: 'b' }],
      edges: [
        { source: 'a', target: 'b', label: null, conditional: false },
        { source: 'b', target: 'b', label: 'again', conditional: true },
      ],
    };
    const { container } = render(<WorkflowGraph topology={selfLoop} traversed={[{ source: 'b', target: 'b' }]} />);
    expect(container.querySelectorAll('path[data-traversed="true"]')).toHaveLength(1);
  });

  it('ignores a traversed pair that matches no drawn edge, without throwing', () => {
    const { container } = render(
      <WorkflowGraph topology={topology} traversed={[{ source: 'plan', target: '__end__' }]} />,
    );
    expect(container.querySelectorAll('path[data-traversed="true"]')).toHaveLength(0);
  });

  it('shows the taken-path legend entry only when traversed is a non-null array', () => {
    const { rerender } = render(<WorkflowGraph topology={topology} />);
    expect(screen.queryByText('taken path')).not.toBeInTheDocument();

    rerender(<WorkflowGraph topology={topology} traversed={null} />);
    expect(screen.queryByText('taken path')).not.toBeInTheDocument();

    rerender(<WorkflowGraph topology={topology} traversed={[]} />);
    expect(screen.getByText('taken path')).toBeInTheDocument();
  });

  it('pins one dot per sub-command inside its node', () => {
    const { container } = render(<WorkflowGraph topology={topology} dotsByNode={{ review: ['ok', 'failed', 'running'] }} />);
    expect(nodeGroup(container, 'review').querySelectorAll('circle[data-lg-dot]')).toHaveLength(3);
    expect(nodeGroup(container, 'review').querySelector('animate')).not.toBeNull();
  });

  it('survives nodes named after Object.prototype members with an empty dotsByNode', () => {
    // dotsByNode?.[node.id] on a plain object inherits Object.prototype's own
    // members (constructor, toString, …) instead of returning undefined —
    // dotRow would then be called with a non-array and throw (§12 rule 2).
    const ids = ['constructor', 'toString', '__proto__', 'hasOwnProperty'];
    render(
      <WorkflowGraph
        topology={{
          nodes: ids.map((id) => ({ id })),
          edges: [{ source: 'constructor', target: 'toString', label: null, conditional: false }],
        }}
        dotsByNode={{}}
      />,
    );
    for (const id of ids) {
      expect(screen.getAllByText(id).length).toBeGreaterThanOrEqual(1);
    }
  });

  it('shows the fallback when the graph cannot be drawn', () => {
    const nodes = Array.from({ length: 201 }, (_, i) => ({ id: `n${i}` }));
    render(<WorkflowGraph topology={{ nodes, edges: [] }} fallback={<p>list instead</p>} />);
    expect(screen.getByText('list instead')).toBeInTheDocument();
  });

  it('says why when there is no fallback', () => {
    const nodes = Array.from({ length: 201 }, (_, i) => ({ id: `n${i}` }));
    render(<WorkflowGraph topology={{ nodes, edges: [] }} />);
    expect(screen.getByText('Graph could not be drawn (201 nodes, 0 edges).')).toBeInTheDocument();
  });
});

describe('WorkflowGraph nodeDetails popover', () => {
  const nodeDetails = (id: string) => (id === 'plan' ? <p>plan details</p> : null);

  it('renders no trigger buttons without nodeDetails', () => {
    render(<WorkflowGraph topology={topology} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('only adds a trigger button for nodes with non-null nodeDetails, never start/end', () => {
    render(<WorkflowGraph topology={topology} nodeDetails={nodeDetails} />);
    expect(screen.getByRole('button', { name: 'Show runs for plan' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Show runs for review/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Show runs for __start__/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Show runs for __end__/ })).not.toBeInTheDocument();
  });

  it('opens the popover on click and closes on Escape', async () => {
    const user = userEvent.setup();
    render(<WorkflowGraph topology={topology} nodeDetails={nodeDetails} />);
    expect(screen.queryByText('plan details')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Show runs for plan' }));
    expect(screen.getByText('plan details')).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByText('plan details')).not.toBeInTheDocument();
  });

  it('opens on Enter when the trigger is focused', async () => {
    const user = userEvent.setup();
    render(<WorkflowGraph topology={topology} nodeDetails={nodeDetails} />);

    await user.tab();
    expect(screen.getByRole('button', { name: 'Show runs for plan' })).toHaveFocus();

    await user.keyboard('{Enter}');
    expect(screen.getByText('plan details')).toBeInTheDocument();
  });

  it('closes the first popover when a second node opens', async () => {
    const user = userEvent.setup();
    const details = (id: string) => (id === 'plan' || id === 'review' ? <p>{id} details</p> : null);
    render(<WorkflowGraph topology={topology} nodeDetails={details} />);

    await user.click(screen.getByRole('button', { name: 'Show runs for plan' }));
    expect(screen.getByText('plan details')).toBeInTheDocument();

    // Real click sequence on both triggers: the first popover's deferred
    // outside-dismiss must not clobber the second one's just-opened state.
    await user.click(screen.getByRole('button', { name: 'Show runs for review' }));
    expect(screen.getByText('review details')).toBeInTheDocument();
    expect(screen.queryByText('plan details')).not.toBeInTheDocument();

    // The first popover's FocusScope schedules its onCloseAutoFocus from a
    // setTimeout(0); flush it so a late, wrongly-unguarded dismiss of the
    // second popover would show up here instead of only in a later test.
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(screen.getByText('review details')).toBeInTheDocument();
    expect(screen.queryByText('plan details')).not.toBeInTheDocument();
  });

  it('returns focus to the trigger after Escape closes its (solo) popover', async () => {
    const user = userEvent.setup();
    render(<WorkflowGraph topology={topology} nodeDetails={nodeDetails} />);
    const trigger = screen.getByRole('button', { name: 'Show runs for plan' });

    await user.click(trigger);
    expect(screen.getByText('plan details')).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByText('plan details')).not.toBeInTheDocument();
    await waitFor(() => expect(trigger).toHaveFocus());
  });

  it('labels the open popover with the node it belongs to', async () => {
    const user = userEvent.setup();
    render(<WorkflowGraph topology={topology} nodeDetails={nodeDetails} />);

    await user.click(screen.getByRole('button', { name: 'Show runs for plan' }));
    expect(screen.getByRole('dialog', { name: 'Runs for plan' })).toBeInTheDocument();
  });
});
