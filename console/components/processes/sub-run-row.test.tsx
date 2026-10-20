import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SubRunRow } from './sub-run-row';
import type { LangGraphSubProgress } from '@/types/homer';

const sub: LangGraphSubProgress = {
  interruptId: 'i1',
  processId: 42,
  agentName: 'agentA',
  status: 'FINISHED',
};

describe('SubRunRow', () => {
  it('links the agent name and the process id', () => {
    render(<SubRunRow sub={sub} />);
    const agentLink = screen.getByRole('link', { name: 'agentA' });
    expect(agentLink).toHaveAttribute('href', '/agents/agentA');
    const processLink = screen.getByRole('link', { name: /42/ });
    expect(processLink).toHaveAttribute('href', '/processes/42');
    expect(screen.getByText(/FINISHED/)).toBeInTheDocument();
  });

  it('gives both links an underlined affordance so they read as links on muted text', () => {
    render(<SubRunRow sub={sub} />);
    expect(screen.getByRole('link', { name: 'agentA' }).className).toContain('underline');
    expect(screen.getByRole('link', { name: /42/ }).className).toContain('underline');
  });

  it('shows "sub" without a link when agentName is null', () => {
    render(<SubRunRow sub={{ ...sub, agentName: null }} />);
    expect(screen.getByText('sub')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'sub' })).not.toBeInTheDocument();
  });

  it('omits the process link when processId is null', () => {
    render(<SubRunRow sub={{ ...sub, processId: null }} />);
    expect(screen.queryByRole('link', { name: /#/ })).not.toBeInTheDocument();
  });

  it('shows "purged" when status is null', () => {
    render(<SubRunRow sub={{ ...sub, status: null }} />);
    expect(screen.getByText(/purged/)).toBeInTheDocument();
  });

  it('renders a hostile agent name as text with an encoded href, never as markup', () => {
    const evil = '<img src=x onerror=alert(1)>';
    const { container } = render(<SubRunRow sub={{ ...sub, agentName: evil }} />);
    expect(container.querySelector('img')).toBeNull();
    const link = screen.getByRole('link', { name: evil });
    expect(link).toHaveAttribute('href', `/agents/${encodeURIComponent(evil)}`);
  });

  it('prefixes the node name when given one', () => {
    render(<SubRunRow sub={sub} node="fanout" />);
    expect(screen.getByText('fanout:')).toBeInTheDocument();
  });

  it('renders command progress dots', () => {
    render(
      <SubRunRow
        sub={{ ...sub, commands: [{ label: 'setup', state: 'ok' }, { label: 'claude', state: 'running' }] }}
      />,
    );
    const row = screen.getByLabelText('command progress');
    expect(row.children).toHaveLength(2);
  });
});
