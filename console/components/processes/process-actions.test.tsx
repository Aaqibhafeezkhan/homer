import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { ProcessActions } from './process-actions';
import type { ProcessInfo } from '@/types/homer';

vi.mock('@/lib/auth/use-can', () => ({ useCan: vi.fn() }));
import { useCan, type Capabilities } from '@/lib/auth/use-can';
import { fakeCapabilities } from '@/lib/auth/capabilities-test-utils';

function mockCan(overrides: Partial<Capabilities> = {}) {
  vi.mocked(useCan).mockReturnValue(fakeCapabilities(true, overrides));
}

function makeProcess(overrides: Partial<ProcessInfo> = {}): ProcessInfo {
  return {
    id: 42,
    status: 'FINISHED',
    agentName: 'demo-agent',
    owner: null,
    history: [{ timestamp: 1_700_000_002, status: 'FINISHED' }],
    executions: [],
    currentCommand: null,
    currentCommandStart: null,
    purgeAt: null,
    ...overrides,
  };
}

function renderActions(
  process: ProcessInfo,
  props: Partial<React.ComponentProps<typeof ProcessActions>> = {}
) {
  const onDownloadArtifactClick = vi.fn();
  const onRetryClick = vi.fn();
  const onKillClick = vi.fn();
  const onResumeClick = vi.fn();
  render(
    <ProcessActions
      process={process}
      onDownloadArtifactClick={onDownloadArtifactClick}
      onRetryClick={onRetryClick}
      onKillClick={onKillClick}
      retryPending={false}
      canResume={false}
      onResumeClick={onResumeClick}
      resumePending={false}
      {...props}
    />
  );
  return { onDownloadArtifactClick, onRetryClick, onKillClick, onResumeClick };
}

describe('ProcessActions', () => {
  beforeEach(() => {
    mockCan();
  });

  it('always renders the download button and fires its callback', () => {
    const { onDownloadArtifactClick } = renderActions(makeProcess());
    const button = screen.getByLabelText('Download artifact for process 42');
    fireEvent.click(button);
    expect(onDownloadArtifactClick).toHaveBeenCalledOnce();
  });

  it('shows retry but not kill for a FINISHED process', () => {
    renderActions(makeProcess({ status: 'FINISHED' }));
    expect(screen.getByLabelText('Retry process 42')).toBeInTheDocument();
    expect(screen.queryByLabelText('Kill process 42')).not.toBeInTheDocument();
  });

  it('shows retry for FAILED and KILLED processes', () => {
    const { rerender } = render(
      <ProcessActions
        process={makeProcess({ status: 'FAILED' })}
        onDownloadArtifactClick={vi.fn()}
        onRetryClick={vi.fn()}
        onKillClick={vi.fn()}
        retryPending={false}
        canResume={false}
        onResumeClick={vi.fn()}
        resumePending={false}
      />
    );
    expect(screen.getByLabelText('Retry process 42')).toBeInTheDocument();

    rerender(
      <ProcessActions
        process={makeProcess({ status: 'KILLED' })}
        onDownloadArtifactClick={vi.fn()}
        onRetryClick={vi.fn()}
        onKillClick={vi.fn()}
        retryPending={false}
        canResume={false}
        onResumeClick={vi.fn()}
        resumePending={false}
      />
    );
    expect(screen.getByLabelText('Retry process 42')).toBeInTheDocument();
  });

  it('shows kill but not retry for WORKING and CREATED processes', () => {
    const { rerender } = render(
      <ProcessActions
        process={makeProcess({ status: 'WORKING' })}
        onDownloadArtifactClick={vi.fn()}
        onRetryClick={vi.fn()}
        onKillClick={vi.fn()}
        retryPending={false}
        canResume={false}
        onResumeClick={vi.fn()}
        resumePending={false}
      />
    );
    expect(screen.getByLabelText('Kill process 42')).toBeInTheDocument();
    expect(screen.queryByLabelText('Retry process 42')).not.toBeInTheDocument();

    rerender(
      <ProcessActions
        process={makeProcess({ status: 'CREATED' })}
        onDownloadArtifactClick={vi.fn()}
        onRetryClick={vi.fn()}
        onKillClick={vi.fn()}
        retryPending={false}
        canResume={false}
        onResumeClick={vi.fn()}
        resumePending={false}
      />
    );
    expect(screen.getByLabelText('Kill process 42')).toBeInTheDocument();
    expect(screen.queryByLabelText('Retry process 42')).not.toBeInTheDocument();
  });

  it('fires retry and kill callbacks', () => {
    const { onRetryClick } = renderActions(makeProcess({ status: 'FAILED' }));
    fireEvent.click(screen.getByLabelText('Retry process 42'));
    expect(onRetryClick).toHaveBeenCalledOnce();

    const { onKillClick } = renderActions(makeProcess({ status: 'WORKING' }));
    fireEvent.click(screen.getByLabelText('Kill process 42'));
    expect(onKillClick).toHaveBeenCalledOnce();
  });

  it('disables the retry button while a retry is pending', () => {
    renderActions(makeProcess({ status: 'FAILED' }), { retryPending: true });
    expect(screen.getByLabelText('Retry process 42')).toBeDisabled();
  });

  it('hides Kill for a WORKING process the user cannot control', () => {
    mockCan({ canKillProcess: () => false, canRetryProcess: () => false });
    renderActions(makeProcess({ status: 'WORKING' }));
    expect(screen.queryByLabelText('Kill process 42')).not.toBeInTheDocument();
  });

  it('shows Kill for a WORKING process the user can control', () => {
    mockCan({ canKillProcess: () => true, canRetryProcess: () => false });
    renderActions(makeProcess({ status: 'WORKING' }));
    expect(screen.getByLabelText('Kill process 42')).toBeInTheDocument();
  });

  it('hides Retry for a FINISHED process the user may not retry', () => {
    mockCan({ canKillProcess: () => false, canRetryProcess: () => false });
    renderActions(makeProcess({ status: 'FINISHED' }));
    expect(screen.queryByLabelText('Retry process 42')).not.toBeInTheDocument();
  });

  it('shows Retry for a FINISHED process the user may retry', () => {
    mockCan({ canKillProcess: () => false, canRetryProcess: () => true });
    renderActions(makeProcess({ status: 'FINISHED' }));
    expect(screen.getByLabelText('Retry process 42')).toBeInTheDocument();
  });

  it('shows Resume only when canResume', () => {
    const { onResumeClick } = renderActions(makeProcess({ status: 'FAILED' }), {
      canResume: true,
    });
    const button = screen.getByLabelText('Resume process 42');
    expect(button).toBeInTheDocument();
    fireEvent.click(button);
    expect(onResumeClick).toHaveBeenCalledOnce();
  });

  it('hides Resume when canResume is false', () => {
    renderActions(makeProcess({ status: 'FAILED' }), { canResume: false });
    expect(screen.queryByLabelText('Resume process 42')).not.toBeInTheDocument();
  });

  it('hides Resume when the user may not retry, even if canResume is true', () => {
    mockCan({ canRetryProcess: () => false });
    renderActions(makeProcess({ status: 'FAILED' }), { canResume: true });
    expect(screen.queryByLabelText('Resume process 42')).not.toBeInTheDocument();
  });

  it('disables the resume button while a resume is pending', () => {
    renderActions(makeProcess({ status: 'FAILED' }), { canResume: true, resumePending: true });
    expect(screen.getByLabelText('Resume process 42')).toBeDisabled();
  });

  describe('a11y', () => {
    it('has no violations with retry and download buttons', async () => {
      const { container } = render(
        <ProcessActions
          process={makeProcess({ status: 'FAILED' })}
          onDownloadArtifactClick={vi.fn()}
          onRetryClick={vi.fn()}
          onKillClick={vi.fn()}
          retryPending={false}
          canResume={true}
          onResumeClick={vi.fn()}
          resumePending={false}
        />
      );
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});
