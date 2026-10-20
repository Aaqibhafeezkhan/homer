import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AgentFilter } from './agent-filter';

const useAgentsMock = vi.hoisted(() => vi.fn());

vi.mock('@/lib/api/queries', () => ({
  useAgents: useAgentsMock,
}));

describe('AgentFilter', () => {
  beforeEach(() => {
    useAgentsMock.mockReturnValue({
      data: { agents: [{ name: 'alpha' }, { name: 'beta' }] },
    });
  });

  it('carries the accessible label and lists agents plus an "All agents" entry', async () => {
    const user = userEvent.setup();
    render(<AgentFilter value="" onChange={() => {}} />);

    const trigger = screen.getByRole('combobox', { name: 'Filter by agent' });
    await user.click(trigger);

    expect(screen.getByRole('option', { name: 'All agents' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'alpha' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'beta' })).toBeInTheDocument();
  });

  it('calls onChange with the agent name when an agent is picked', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<AgentFilter value="" onChange={onChange} />);

    await user.click(screen.getByRole('combobox', { name: 'Filter by agent' }));
    await user.click(screen.getByRole('option', { name: 'beta' }));

    expect(onChange).toHaveBeenCalledWith('beta');
  });

  it('calls onChange with an empty string when "All agents" is picked', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<AgentFilter value="alpha" onChange={onChange} />);

    await user.click(screen.getByRole('combobox', { name: 'Filter by agent' }));
    await user.click(screen.getByRole('option', { name: 'All agents' }));

    expect(onChange).toHaveBeenCalledWith('');
  });

  it('disables the trigger while agent data has not loaded', () => {
    useAgentsMock.mockReturnValue({ data: undefined });
    render(<AgentFilter value="" onChange={() => {}} />);

    expect(screen.getByRole('combobox', { name: 'Filter by agent' })).toBeDisabled();
  });
});
