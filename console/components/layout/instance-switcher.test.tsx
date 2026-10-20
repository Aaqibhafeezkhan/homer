import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const switchInstance = vi.fn();
const push = vi.fn();
const useInstancesMock = vi.hoisted(() => vi.fn());

vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('@/lib/auth/auth-context', () => ({ useAuth: () => ({ switchInstance }) }));
vi.mock('@/lib/instances/use-instances', () => ({ useInstances: () => useInstancesMock() }));

import { InstanceSwitcher } from './instance-switcher';

const instances = [
  { id: 'default', label: 'Default', baseUrl: 'http://localhost:8080', mode: 'cookie' },
  { id: 'remote', label: 'Remote', baseUrl: 'https://b.example.com', mode: 'bearer' },
];

describe('InstanceSwitcher', () => {
  beforeEach(() => {
    switchInstance.mockReset();
    push.mockReset();
    useInstancesMock.mockReturnValue({ instances, activeInstanceId: 'default' });
  });

  it('shows the active instance label on the trigger', () => {
    render(<InstanceSwitcher />);
    expect(screen.getByRole('button', { name: /select instance/i })).toHaveTextContent('Default');
  });

  it('lists instances and marks the active one', async () => {
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    expect(screen.getByRole('menuitemradio', { name: /Default/ })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(screen.getByRole('menuitemradio', { name: /Remote/ })).toHaveAttribute(
      'aria-checked',
      'false',
    );
  });

  it('switches and routes to /processes when picking another instance', async () => {
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    await user.click(screen.getByRole('menuitemradio', { name: /Remote/ }));
    expect(switchInstance).toHaveBeenCalledWith('remote');
    expect(push).toHaveBeenCalledWith('/processes');
  });

  it('does not switch when picking the already-active instance', async () => {
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    await user.click(screen.getByRole('menuitemradio', { name: /Default/ }));
    expect(switchInstance).not.toHaveBeenCalled();
  });

  it('routes to /login to add an instance', async () => {
    const user = userEvent.setup();
    render(<InstanceSwitcher />);
    await user.click(screen.getByRole('button', { name: /select instance/i }));
    await user.click(screen.getByRole('menuitem', { name: /add instance/i }));
    expect(push).toHaveBeenCalledWith('/login');
  });
});
