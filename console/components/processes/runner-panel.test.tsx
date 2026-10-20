import { describe, it, expect, vi } from 'vitest';
import { act, fireEvent, render, screen } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { RunnerPanel } from './runner-panel';
import type { RunnerState } from '@/types/homer';

function make(overrides: Partial<RunnerState> = {}): RunnerState {
  return {
    alias: 'k8s-dev',
    type: 'kubernetes',
    podName: 'homer-42-abcdef',
    podPhase: 'READY',
    createdAt: 1_700_000_000,
    readyAt: 1_700_000_005,
    ...overrides,
  };
}

describe('RunnerPanel', () => {
  it('renders alias, pod name, and phase label', () => {
    render(<RunnerPanel runner={make()} />);

    expect(screen.getByText('k8s-dev')).toBeInTheDocument();
    expect(screen.getByText('homer-42-abcdef')).toBeInTheDocument();
    expect(screen.getByLabelText('Pod phase: Ready')).toBeInTheDocument();
  });

  it('hides pod block for local runner without pod name', () => {
    render(
      <RunnerPanel
        runner={make({ alias: 'local', podName: null, podPhase: 'NONE' })}
      />
    );

    expect(screen.getByText('local')).toBeInTheDocument();
    expect(screen.queryByLabelText('Copy pod name')).not.toBeInTheDocument();
  });

  it('renders the generic backend label for a non-admin viewer with no alias', () => {
    // A non-admin viewer receives `alias: null` and the generic
    // backend type; the panel must render the type under a "Backend:" label.
    render(
      <RunnerPanel
        runner={make({
          alias: null,
          type: 'kubernetes',
          podName: null,
          podPhase: 'NONE',
          createdAt: null,
          readyAt: null,
          endedAt: null,
        })}
      />
    );

    expect(screen.getByText('Backend:')).toBeInTheDocument();
    expect(screen.getByText('kubernetes')).toBeInTheDocument();
    expect(screen.queryByText('Alias:')).not.toBeInTheDocument();
  });

  it('renders an alias-only runner without a phase badge or pod-only rows', () => {
    render(
      <RunnerPanel
        runner={make({
          alias: 'local',
          podName: null,
          podPhase: 'NONE',
          createdAt: null,
          readyAt: null,
          endedAt: null,
        })}
      />
    );

    expect(screen.getByText('local')).toBeInTheDocument();
    // The placeholder NONE phase must not surface as a meaningless "None" badge.
    expect(screen.queryByLabelText('Pod phase: None')).not.toBeInTheDocument();
    expect(screen.queryByText('Created:')).not.toBeInTheDocument();
    expect(screen.queryByText('Ready:')).not.toBeInTheDocument();
    expect(screen.queryByText('Ended:')).not.toBeInTheDocument();
  });

  it('still renders the phase badge for a pod-bearing runner', () => {
    render(<RunnerPanel runner={make({ alias: 'local', podName: 'homer-1-x', podPhase: 'READY' })} />);

    expect(screen.getByLabelText('Pod phase: Ready')).toBeInTheDocument();
  });

  it('hides the startup-failure block for a runner without a pod', () => {
    // A persisted pre-create failure can carry startupFailure on an
    // otherwise pod-less runner; the panel claims the run had no pod, so the
    // pod-only details block must not surface.
    render(
      <RunnerPanel
        runner={make({
          podName: null,
          podPhase: 'NONE',
          createdAt: null,
          readyAt: null,
          endedAt: null,
          startupFailure: {
            reason: 'ImagePullBackOff',
            message: 'manifest for ubuntu:bogus not found',
            events: [],
          },
        })}
      />
    );

    expect(screen.queryByText(/Startup failure: ImagePullBackOff/)).not.toBeInTheDocument();
  });

  it('shows startup failure reason, message, and events when failed', () => {
    render(
      <RunnerPanel
        runner={make({
          podPhase: 'FAILED',
          startupFailure: {
            reason: 'ImagePullBackOff',
            message: 'manifest for ubuntu:bogus not found',
            events: [
              'Warning/Failed: Failed to pull image',
              'Warning/BackOff: Back-off pulling image',
            ],
          },
        })}
      />
    );

    expect(screen.getByText(/Startup failure: ImagePullBackOff/)).toBeInTheDocument();
    expect(screen.getByText(/manifest for ubuntu:bogus not found/)).toBeInTheDocument();
    expect(screen.getByText(/Failed to pull image/)).toBeInTheDocument();
    expect(screen.getByText(/Back-off pulling image/)).toBeInTheDocument();
  });

  it('renders each known pod phase label', () => {
    const phases: Array<[RunnerState['podPhase'], string]> = [
      ['CREATING', 'Creating'],
      ['PENDING', 'Pending'],
      ['RUNNING', 'Running'],
      ['READY', 'Ready'],
      ['FAILED', 'Failed'],
      ['DELETED', 'Deleted'],
      ['KEPT_FOR_DEBUG', 'Kept for debug'],
    ];

    for (const [phase, label] of phases) {
      const { unmount } = render(
        <RunnerPanel runner={make({ podPhase: phase })} />
      );
      expect(screen.getByLabelText(`Pod phase: ${label}`)).toBeInTheDocument();
      unmount();
    }
  });

  describe('a11y', () => {
    it('has no violations for a ready k8s runner with pod copy affordance', async () => {
      const { container } = render(<RunnerPanel runner={make()} />);
      expect(await axe(container)).toHaveNoViolations();
    });

    it('has no violations for an alias-only non-k8s runner', async () => {
      const { container } = render(
        <RunnerPanel
          runner={make({
            alias: 'local',
            podName: null,
            podPhase: 'NONE',
            createdAt: null,
            readyAt: null,
            endedAt: null,
          })}
        />,
      );
      expect(await axe(container)).toHaveNoViolations();
    });

    it('has no violations for a failed runner with startup failure events', async () => {
      const { container } = render(
        <RunnerPanel
          runner={make({
            podPhase: 'FAILED',
            startupFailure: {
              reason: 'ImagePullBackOff',
              message: 'manifest for ubuntu:bogus not found',
              events: ['Warning/Failed: Failed to pull image'],
            },
          })}
        />,
      );
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});

describe('RunnerPanel pod timestamps', () => {
  it('lists every known pod timestamp in created, ready, ended order', () => {
    const { container } = render(<RunnerPanel runner={make({ endedAt: 1_700_000_060 })} />);
    const labels = [...container.querySelectorAll('.border-t .font-medium')].map((el) => el.textContent);
    expect(labels).toEqual(['Created:', 'Ready:', 'Ended:']);
  });

  it('leaves out a timestamp the pod has not reached', () => {
    render(<RunnerPanel runner={make({ readyAt: null, endedAt: null })} />);
    expect(screen.getByText('Created:')).toBeInTheDocument();
    expect(screen.queryByText('Ready:')).not.toBeInTheDocument();
    expect(screen.queryByText('Ended:')).not.toBeInTheDocument();
  });

  it('renders no timestamp section for a pod with no timestamps yet', () => {
    const { container } = render(
      <RunnerPanel runner={make({ podPhase: 'CREATING', createdAt: null, readyAt: null, endedAt: null })} />,
    );
    expect(screen.getByLabelText('Pod phase: Creating')).toBeInTheDocument();
    expect(container.querySelector('.border-t')).toBeNull();
  });

  it('copies the pod name and shows the copied check', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    const { container } = render(<RunnerPanel runner={make()} />);
    const button = screen.getByLabelText('Copy pod name');
    expect(button.querySelector('.lucide-copy')).not.toBeNull();

    await act(async () => {
      fireEvent.click(button);
    });

    expect(writeText).toHaveBeenCalledWith('homer-42-abcdef');
    expect(container.querySelector('.lucide-check')).not.toBeNull();
  });

  it('ignores a clipboard that refuses the write', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('insecure context'));
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });
    render(<RunnerPanel runner={make()} />);
    const button = screen.getByLabelText('Copy pod name');

    await act(async () => {
      fireEvent.click(button);
    });

    expect(writeText).toHaveBeenCalled();
    expect(button.querySelector('.lucide-copy')).not.toBeNull();
  });
});
