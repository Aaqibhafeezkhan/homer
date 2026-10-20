import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { WorkflowStatus } from './workflow-status';
import type { LangGraphRunStatus } from '@/types/homer';

const base: LangGraphRunStatus = {
  label: 'flow',
  module: 'm:graph',
  threadId: 't1',
  topology: {
    nodes: [{ id: 'a' }, { id: 'b' }, { id: 'c' }],
    edges: [{ source: 'a', target: 'b' }, { source: 'b', target: 'c' }],
  },
  nodes: { a: 'done', b: 'failed', c: 'unreached' },
  errors: { b: 'RuntimeError: boom' },
};

// A node's <title> duplicates its <text> label, so a node group is found by
// iterating [data-node-id] groups rather than a CSS.escape'd selector (jsdom
// may not implement CSS.escape).
const nodeGroup = (container: HTMLElement, id: string): Element => {
  const groups = container.querySelectorAll('[data-node-id]');
  for (const g of Array.from(groups)) {
    if (g.getAttribute('data-node-id') === id) return g;
  }
  throw new Error(`no node group for ${id}`);
};

const rectClass = (container: HTMLElement, id: string) =>
  nodeGroup(container, id).querySelector('rect')!.getAttribute('class') ?? '';

/** The sub list is collapsed by default; open it via its summary toggle. */
function expandSubs() {
  fireEvent.click(screen.getByRole('button', { name: /subs finished/ }));
}

describe('WorkflowStatus', () => {
  it('tints statused nodes and leaves unreached ones plain', () => {
    const { container } = render(<WorkflowStatus status={base} />);
    expect(rectClass(container, 'a')).toContain('fill-green-500/15');
    expect(rectClass(container, 'a')).not.toContain('fill-card'); // tint replaces the default fill, not just adds to it
    expect(rectClass(container, 'b')).toContain('fill-red-500/15');
    expect(rectClass(container, 'c')).toContain('fill-card');
  });

  it('forwards traversed edges to the graph, rendering a taken edge', () => {
    const { container } = render(
      <WorkflowStatus status={{ ...base, traversed: [{ source: 'a', target: 'b' }] }} />
    );
    expect(container.querySelectorAll('path[data-traversed="true"]')).toHaveLength(1);
  });

  it('addresses special-character node names directly', () => {
    const status = {
      ...base,
      topology: { nodes: [{ id: 'review step' }], edges: [] },
      nodes: { 'review step': 'done' },
      errors: {},
    };
    const { container } = render(<WorkflowStatus status={status} />);
    expect(rectClass(container, 'review step')).toContain('fill-green-500/15');
  });

  it('shows node error messages', () => {
    render(<WorkflowStatus status={base} />);
    expect(screen.getByText(/RuntimeError: boom/)).toBeInTheDocument();
  });

  it('renders the backend error without a diagram', () => {
    render(<WorkflowStatus status={{ ...base, nodes: null, error: 'state timed out' }} />);
    expect(screen.getByText(/state timed out/)).toBeInTheDocument();
    expect(screen.queryByRole('img', { name: 'Workflow graph' })).not.toBeInTheDocument();
  });

  it('renders a loading hint when nodes are not yet loaded and no error', () => {
    render(<WorkflowStatus status={{ ...base, nodes: null, error: null }} />);
    expect(screen.getByText(/loading/i)).toBeInTheDocument();
  });

  it('falls back to the node list without topology', () => {
    render(<WorkflowStatus status={{ ...base, topology: null }} />);
    expect(screen.getByLabelText('node statuses')).toBeInTheDocument();
  });

  it('falls back to the node list when the graph is above the render cap', () => {
    const nodes = Array.from({ length: 201 }, (_, i) => ({ id: `n${i}` }));
    render(
      <WorkflowStatus
        status={{
          ...base,
          topology: { nodes, edges: [] },
          nodes: { n0: 'done' },
          errors: {},
        }}
      />
    );
    expect(screen.getByLabelText('node statuses')).toBeInTheDocument();
  });

  it('survives and tints a node named after an Object.prototype member', () => {
    // Node ids are agent-authored; "constructor" must resolve through an
    // own-property check, not fall through to the inherited function (§12
    // rule 2) — a plain object with no "constructor" key still inherits one.
    // JSON.parse gives "__proto__" an own key, as the API response does; a
    // literal would set the prototype instead.
    const status = {
      ...base,
      topology: { nodes: [{ id: 'constructor' }, { id: '__proto__' }], edges: [] },
      nodes: JSON.parse('{"constructor":"parked","__proto__":"parked"}'),
      errors: {},
      // Non-null, deliberately lacking an own "constructor" entry.
      subs: JSON.parse(
        '{"__proto__":[{"interruptId":"i1","processId":1,"agentName":"a","status":"WORKING",' +
          '"commands":[{"label":"setup","state":"ok"},{"label":"claude","state":"running"}]}]}'
      ),
    };
    const { container } = render(<WorkflowStatus status={status} />);
    expect(rectClass(container, 'constructor')).toContain('fill-amber-500/15');
    expect(nodeGroup(container, 'constructor').querySelectorAll('[data-lg-dot]')).toHaveLength(0);
    expect(nodeGroup(container, '__proto__').querySelectorAll('[data-lg-dot]')).toHaveLength(2);
  });

  it('keeps rendering subs, errors and thread id when falling back to the node list', () => {
    render(
      <WorkflowStatus
        status={{
          ...base,
          topology: null,
          subs: {
            a: [{ interruptId: 'i1', processId: 7, agentName: 'agentA', status: 'FINISHED' }],
          },
        }}
      />
    );
    expect(screen.queryByRole('img', { name: 'Workflow graph' })).not.toBeInTheDocument();
    expect(screen.queryByText(/loading/i)).not.toBeInTheDocument();
    const list = screen.getByLabelText('node statuses');
    expect(list).toHaveTextContent('a — done');
    expect(list).toHaveTextContent('b — failed');
    // the rest of the overlay still renders: subs, errors, thread id
    expect(screen.getByText(/1\/1 subs finished/)).toBeInTheDocument();
    expect(screen.getByText(/RuntimeError: boom/)).toBeInTheDocument();
  });

  it('renders per-sub progress lines and an aggregate finished counter', () => {
    render(
      <WorkflowStatus
        status={{
          ...base,
          subs: {
            fanout: [
              { interruptId: 'i1', processId: 42, agentName: 'agentA', status: 'FINISHED' },
              { interruptId: 'i2', processId: null, agentName: 'agentB', status: 'WORKING' },
            ],
          },
        }}
      />
    );
    // The counter is always visible; the per-sub lines are behind the toggle.
    expect(screen.getByText(/1\/2 subs finished/)).toBeInTheDocument();
    expect(screen.queryByText(/agentA/)).not.toBeInTheDocument();

    expandSubs();
    expect(screen.getByText(/agentA/)).toBeInTheDocument();
    expect(screen.getByText(/agentB/)).toBeInTheDocument();
    expect(screen.getByText(/WORKING/)).toBeInTheDocument();

    // ...and collapsing hides them again.
    expandSubs();
    expect(screen.queryByText(/agentA/)).not.toBeInTheDocument();
  });

  it('links a sub with a processId to its process detail page, and its agent name to the agent page', () => {
    render(
      <WorkflowStatus
        status={{
          ...base,
          subs: {
            fanout: [{ interruptId: 'i1', processId: 42, agentName: 'agentA', status: 'FINISHED' }],
          },
        }}
      />
    );
    expandSubs();
    const link = screen.getByRole('link', { name: /42/ });
    expect(link).toHaveAttribute('href', '/processes/42');
    expect(link).toHaveAttribute('aria-label', 'Open process 42 details');
    const agentLink = screen.getByRole('link', { name: 'agentA' });
    expect(agentLink).toHaveAttribute('href', '/agents/agentA');
  });

  it('labels a sub whose process was evicted from the store as purged', () => {
    render(
      <WorkflowStatus
        status={{
          ...base,
          subs: {
            fanout: [{ interruptId: 'i1', processId: 8, agentName: null, status: null }],
          },
        }}
      />
    );
    expandSubs();
    expect(screen.getByText(/purged/)).toBeInTheDocument();
  });

  it('renders no sub section when subs is absent', () => {
    render(<WorkflowStatus status={base} />);
    expect(screen.queryByText(/subs finished/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Older sub history/)).not.toBeInTheDocument();
  });

  it('discloses nodes whose sub history the runtime trimmed', () => {
    render(
      <WorkflowStatus
        status={{
          ...base,
          subs: {
            loop: [{ interruptId: 'i9', processId: 3, agentName: 'agentA', status: 'FINISHED' }],
          },
          subsTruncated: ['loop'],
        }}
      />
    );
    expect(screen.getByText(/Older sub history not shown for: loop/)).toBeInTheDocument();
  });

  it('discloses trimmed history even when no sub line matched', () => {
    // The cap drops interrupt ids; whether any of them had a continuation to
    // resolve is independent, so the notice must not hide behind the subs list.
    render(<WorkflowStatus status={{ ...base, subs: null, subsTruncated: ['loop', 'gate'] }} />);
    expect(screen.queryByText(/subs finished/)).not.toBeInTheDocument();
    expect(screen.getByText(/Older sub history not shown for: loop, gate/)).toBeInTheDocument();
  });

  it('colors a parked node sub-done once all its subs finished, individually per node', () => {
    const status = {
      ...base,
      topology: { nodes: [{ id: 'fan_a' }, { id: 'fan_b' }, { id: 'fan_c' }], edges: [] },
      nodes: { fan_a: 'parked', fan_b: 'parked', fan_c: 'parked' },
      errors: {},
      subs: {
        fan_a: [{ interruptId: 'i1', processId: 1, agentName: 'a', status: 'FINISHED' }],
        fan_b: [{ interruptId: 'i2', processId: 2, agentName: 'b', status: 'WORKING' }],
        // fan_c: no sub info at all (e.g. continuation not registered yet)
      },
    };
    const { container } = render(<WorkflowStatus status={status} />);
    expect(rectClass(container, 'fan_a')).toContain('stroke-amber-600'); // finished sub -> subDone
    expect(rectClass(container, 'fan_a')).toContain('[stroke-dasharray:4_3]');
    expect(rectClass(container, 'fan_b')).toContain('fill-amber-500/15'); // still-working sub stays parked
    expect(rectClass(container, 'fan_b')).not.toContain('[stroke-dasharray:4_3]');
    expect(rectClass(container, 'fan_c')).toContain('fill-amber-500/15'); // unknown sub state stays parked
  });

  it('renders per-command indicator dots with label tooltips for a sub', () => {
    render(
      <WorkflowStatus
        status={{
          ...base,
          nodes: { fan_a: 'parked' },
          errors: {},
          subs: {
            fan_a: [
              {
                interruptId: 'i1',
                processId: 1,
                agentName: 'a',
                status: 'WORKING',
                commands: [
                  { label: 'setup', state: 'ok' },
                  { label: 'lint', state: 'failed' },
                  { label: 'claude', state: 'running' },
                ],
              },
            ],
          },
        }}
      />
    );
    expandSubs();
    const row = screen.getByLabelText('command progress');
    expect(row.children).toHaveLength(3);
    expect(row.children[0]).toHaveAttribute('title', 'setup: ok');
    expect(row.children[1]).toHaveAttribute('title', 'lint: failed');
    expect(row.children[2]).toHaveAttribute('title', 'claude: running');
    // Same palette as the in-graph dots (shared commandDotColor source).
    expect((row.children[0] as HTMLElement).style.backgroundColor).toBe('rgb(22, 163, 74)');
    expect((row.children[1] as HTMLElement).style.backgroundColor).toBe('rgb(220, 38, 38)');
    expect((row.children[2] as HTMLElement).style.backgroundColor).toBe('rgb(37, 99, 235)');
  });

  it('pins the sub-command dots into the matching graph node box', () => {
    const status = {
      ...base,
      topology: { nodes: [{ id: 'fan_a' }], edges: [] },
      nodes: { fan_a: 'parked' },
      errors: {},
      subs: {
        fan_a: [
          {
            interruptId: 'i1',
            processId: 1,
            agentName: 'a',
            status: 'WORKING',
            commands: [
              { label: 'setup', state: 'ok' },
              { label: 'claude', state: 'running' },
            ],
          },
        ],
      },
    };
    const { container } = render(<WorkflowStatus status={status} />);
    const dots = nodeGroup(container, 'fan_a').querySelectorAll('circle[data-lg-dot]');
    expect(dots).toHaveLength(2);
    // Same palette as the sub-progress row (shared commandDotColor source).
    expect((dots[0] as SVGElement).style.fill).toBe('rgb(22, 163, 74)');
    expect((dots[1] as SVGElement).style.fill).toBe('rgb(37, 99, 235)');
  });

  it('renders no indicator row when a sub has no command info', () => {
    render(
      <WorkflowStatus
        status={{
          ...base,
          nodes: { fan_a: 'parked' },
          errors: {},
          subs: {
            fan_a: [{ interruptId: 'i1', processId: 1, agentName: 'a', status: 'WORKING' }],
          },
        }}
      />
    );
    expandSubs();
    expect(screen.queryByLabelText('command progress')).not.toBeInTheDocument();
  });

  it('keeps a parked node plain-parked unless every one of its subs finished', () => {
    const status = {
      ...base,
      topology: { nodes: [{ id: 'fan_a' }], edges: [] },
      nodes: { fan_a: 'parked' },
      errors: {},
      subs: {
        fan_a: [
          { interruptId: 'i1', processId: 1, agentName: 'a', status: 'FINISHED' },
          { interruptId: 'i2', processId: 2, agentName: 'a', status: 'FAILED' },
        ],
      },
    };
    const { container } = render(<WorkflowStatus status={status} />);
    expect(rectClass(container, 'fan_a')).toContain('fill-amber-500/15');
    expect(rectClass(container, 'fan_a')).not.toContain('[stroke-dasharray:4_3]');
  });
});

describe('WorkflowStatus node runs popover', () => {
  it('opens a node popover with links to the agent and the process', async () => {
    const user = userEvent.setup();
    render(
      <WorkflowStatus
        status={{
          ...base,
          subs: { a: [{ interruptId: 'i1', processId: 42, agentName: 'agentA', status: 'FINISHED' }] },
        }}
      />
    );
    await user.click(screen.getByRole('button', { name: 'Show runs for a' }));
    const agentLink = screen.getByRole('link', { name: 'agentA' });
    expect(agentLink).toHaveAttribute('href', '/agents/agentA');
    const processLink = screen.getByRole('link', { name: /42/ });
    expect(processLink).toHaveAttribute('href', '/processes/42');
  });

  it('shows "sub" without a link when agentName is null', async () => {
    const user = userEvent.setup();
    render(
      <WorkflowStatus
        status={{
          ...base,
          subs: { a: [{ interruptId: 'i1', processId: 8, agentName: null, status: 'WORKING' }] },
        }}
      />
    );
    await user.click(screen.getByRole('button', { name: 'Show runs for a' }));
    expect(screen.getByText('sub')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'sub' })).not.toBeInTheDocument();
  });

  it('omits the process link when processId is null', async () => {
    const user = userEvent.setup();
    render(
      <WorkflowStatus
        status={{
          ...base,
          subs: { a: [{ interruptId: 'i1', processId: null, agentName: 'agentA', status: 'WORKING' }] },
        }}
      />
    );
    await user.click(screen.getByRole('button', { name: 'Show runs for a' }));
    expect(screen.queryByRole('link', { name: /#/ })).not.toBeInTheDocument();
  });

  it('shows "purged" when status is null', async () => {
    const user = userEvent.setup();
    render(
      <WorkflowStatus
        status={{
          ...base,
          subs: { a: [{ interruptId: 'i1', processId: 8, agentName: null, status: null }] },
        }}
      />
    );
    await user.click(screen.getByRole('button', { name: 'Show runs for a' }));
    expect(screen.getByText(/purged/)).toBeInTheDocument();
  });

  it('has no trigger for a node without subs', () => {
    render(<WorkflowStatus status={base} />);
    expect(screen.queryByRole('button', { name: /Show runs for/ })).not.toBeInTheDocument();
  });

  it('renders a hostile agent name as text with an encoded href, never as markup', async () => {
    const user = userEvent.setup();
    const evil = '<img src=x onerror=alert(1)>';
    const { container } = render(
      <WorkflowStatus
        status={{
          ...base,
          subs: { a: [{ interruptId: 'i1', processId: 1, agentName: evil, status: 'WORKING' }] },
        }}
      />
    );
    await user.click(screen.getByRole('button', { name: 'Show runs for a' }));
    expect(container.querySelector('img')).toBeNull();
    const link = screen.getByRole('link', { name: evil });
    expect(link).toHaveAttribute('href', `/agents/${encodeURIComponent(evil)}`);
  });
});
