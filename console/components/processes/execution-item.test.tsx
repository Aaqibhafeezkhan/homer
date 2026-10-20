import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { ExecutionItem } from './execution-item';
import { formatTimestamp, formatDuration } from '@/lib/utils/format';
import type { ProcessExecution, LangGraphRunStatus } from '@/types/homer';

vi.mock('./workflow-status', () => ({
  WorkflowStatus: () => <div data-testid="workflow-status" />,
}));

const tabsRenders = vi.hoisted(() => ({ count: 0 }));

vi.mock('./artifact-output-tabs', () => ({
  ArtifactOutputTabs: (props: Record<string, unknown>) => {
    tabsRenders.count++;
    return <div data-testid="artifact-output-tabs" data-props={JSON.stringify(props)} />;
  },
}));

function makeWorkflow(overrides: Partial<LangGraphRunStatus> = {}): LangGraphRunStatus {
  return {
    label: 'cmd-0',
    module: 'm:graph',
    threadId: 't1',
    topology: { nodes: [{ id: 'a' }], edges: [] },
    nodes: { a: 'done' },
    errors: null,
    ...overrides,
  };
}

function makeExecution(overrides: Partial<ProcessExecution> = {}): ProcessExecution {
  return {
    label: 'cmd-0',
    start: 1_700_000_000,
    end: 1_700_000_001,
    resultCode: 0,
    stdOut: 'cmd_0.out',
    stdErr: 'cmd_0.err',
    errMsg: null,
    ...overrides,
  };
}

function renderItem(props: Partial<React.ComponentProps<typeof ExecutionItem>> = {}) {
  const onToggle = vi.fn();
  const onDownloadArtifact = vi.fn();
  render(
    <ExecutionItem
      execution={makeExecution()}
      index={0}
      expanded={false}
      onToggle={onToggle}
      processId={42}
      onDownloadArtifact={onDownloadArtifact}
      downloadPending={false}
      {...props}
    />
  );
  return { onToggle, onDownloadArtifact };
}

describe('ExecutionItem', () => {
  it('renders label and exit code when collapsed', () => {
    renderItem({ execution: makeExecution({ label: 'deploy', resultCode: 0 }) });
    expect(screen.getByText('deploy')).toBeInTheDocument();
    expect(screen.getByText('Exit code: 0')).toBeInTheDocument();
    // collapsed: detail section hidden
    expect(screen.queryByTestId('artifact-output-tabs')).not.toBeInTheDocument();
  });

  it('shows start/end timestamps and duration in the collapsed header', () => {
    const execution = makeExecution({ start: 1_700_000_000, end: 1_700_000_042 });
    renderItem({ execution });
    const expected = `${formatTimestamp(execution.start)} → ${formatTimestamp(execution.end)} · ${formatDuration(execution.start, execution.end)}`;
    expect(screen.getByText(expected)).toBeInTheDocument();
  });

  it('shows only the duration in the header for skipped executions', () => {
    const execution = makeExecution({
      skipped: true,
      resultCode: -1,
      start: 1_700_000_000,
      end: 1_700_000_010,
    });
    renderItem({ execution });
    expect(screen.getByText(formatDuration(execution.start, execution.end))).toBeInTheDocument();
    expect(
      screen.queryByText(`${formatTimestamp(execution.start)} → ${formatTimestamp(execution.end)}`, {
        exact: false,
      })
    ).not.toBeInTheDocument();
  });

  it('fires onToggle when clicked', () => {
    const { onToggle } = renderItem({ index: 3 });
    fireEvent.click(screen.getByRole('button'));
    expect(onToggle).toHaveBeenCalledWith(3);
  });

  it('fires onToggle on Enter and Space keydown', () => {
    const { onToggle } = renderItem({ index: 2 });
    const item = screen.getByRole('button');
    fireEvent.keyDown(item, { key: 'Enter' });
    fireEvent.keyDown(item, { key: ' ' });
    expect(onToggle).toHaveBeenCalledTimes(2);
    expect(onToggle).toHaveBeenCalledWith(2);
  });

  it('renders the Skipped icon and label for skipped executions', () => {
    renderItem({
      execution: makeExecution({
        skipped: true,
        resultCode: -1,
        stdOut: null,
        stdErr: null,
        errMsg: 'when.all expression #1 false',
      }),
    });
    expect(screen.getByLabelText('Skipped')).toBeInTheDocument();
    expect(screen.getByText('Skipped')).toBeInTheDocument();
  });

  it('renders ArtifactOutputTabs with the right props when expanded', () => {
    renderItem({
      expanded: true,
      index: 1,
      processId: 99,
      execution: makeExecution({ stdOut: 'cmd_1.out', stdErr: 'cmd_1.err', kind: 'codex' }),
    });
    const tabs = screen.getByTestId('artifact-output-tabs');
    const props = JSON.parse(tabs.getAttribute('data-props') ?? '{}');
    expect(props).toMatchObject({
      processId: 99,
      stdoutPath: 'cmd_1.out',
      stderrPath: 'cmd_1.err',
      executionIndex: 1,
      defaultTab: 'stdout',
      kind: 'codex',
      enabled: true,
      downloadPending: false,
    });
  });

  it('hands the step accounting to ArtifactOutputTabs', () => {
    const llmAccounting = { sessionId: 's', costUsd: 0.008, source: 'session' as const, fallbackReason: null };
    renderItem({ expanded: true, execution: makeExecution({ kind: 'codex', llmAccounting }) });
    const props = JSON.parse(screen.getByTestId('artifact-output-tabs').getAttribute('data-props') ?? '{}');
    expect(props.accounting).toEqual(llmAccounting);
  });

  it('defaults ArtifactOutputTabs to the stderr tab when there is no stdout artifact', () => {
    renderItem({
      expanded: true,
      execution: makeExecution({ stdOut: null, stdErr: 'cmd_1.err' }),
    });
    const tabs = screen.getByTestId('artifact-output-tabs');
    const props = JSON.parse(tabs.getAttribute('data-props') ?? '{}');
    expect(props).toMatchObject({ defaultTab: 'stderr' });
  });

  it('does not render ArtifactOutputTabs when collapsed', () => {
    renderItem({ expanded: false });
    expect(screen.queryByTestId('artifact-output-tabs')).not.toBeInTheDocument();
  });

  it('shows the error row when expanded', () => {
    renderItem({
      expanded: true,
      execution: makeExecution({ errMsg: 'boom' }),
    });
    expect(screen.getByText('Error:')).toBeInTheDocument();
    expect(screen.getByText('boom')).toBeInTheDocument();
  });

  it('shows the "No output artifacts" fallback for a skipped execution with null paths', () => {
    renderItem({
      expanded: true,
      execution: makeExecution({ skipped: true, stdOut: null, stdErr: null }),
    });
    expect(screen.getByText('No output artifacts')).toBeInTheDocument();
    expect(screen.queryByTestId('artifact-output-tabs')).not.toBeInTheDocument();
  });

  it('renders the workflow overlay only when expanded and workflow given', () => {
    const { rerender } = render(
      <ExecutionItem
        execution={makeExecution()}
        index={0}
        expanded={false}
        onToggle={vi.fn()}
        processId={42}
        onDownloadArtifact={vi.fn()}
        downloadPending={false}
        workflow={makeWorkflow()}
      />
    );
    expect(screen.queryByTestId('workflow-status')).not.toBeInTheDocument();

    rerender(
      <ExecutionItem
        execution={makeExecution()}
        index={0}
        expanded={true}
        onToggle={vi.fn()}
        processId={42}
        onDownloadArtifact={vi.fn()}
        downloadPending={false}
        workflow={makeWorkflow()}
      />
    );
    expect(screen.getByTestId('workflow-status')).toBeInTheDocument();

    rerender(
      <ExecutionItem
        execution={makeExecution()}
        index={0}
        expanded={true}
        onToggle={vi.fn()}
        processId={42}
        onDownloadArtifact={vi.fn()}
        downloadPending={false}
        workflow={undefined}
      />
    );
    expect(screen.queryByTestId('workflow-status')).not.toBeInTheDocument();
  });

  it('shows a loading indicator when expanded with workflowLoading and no workflow yet', () => {
    const { rerender } = render(
      <ExecutionItem
        execution={makeExecution()}
        index={0}
        expanded={true}
        onToggle={vi.fn()}
        processId={42}
        onDownloadArtifact={vi.fn()}
        downloadPending={false}
        workflow={undefined}
        workflowLoading={true}
      />
    );
    expect(screen.getByText(/loading workflow status/i)).toBeInTheDocument();

    // Loaded workflow replaces the loading hint even if a refetch is in flight.
    rerender(
      <ExecutionItem
        execution={makeExecution()}
        index={0}
        expanded={true}
        onToggle={vi.fn()}
        processId={42}
        onDownloadArtifact={vi.fn()}
        downloadPending={false}
        workflow={makeWorkflow()}
        workflowLoading={true}
      />
    );
    expect(screen.queryByText(/loading workflow status/i)).not.toBeInTheDocument();
    expect(screen.getByTestId('workflow-status')).toBeInTheDocument();

    // Collapsed rows never show the indicator.
    rerender(
      <ExecutionItem
        execution={makeExecution()}
        index={0}
        expanded={false}
        onToggle={vi.fn()}
        processId={42}
        onDownloadArtifact={vi.fn()}
        downloadPending={false}
        workflow={undefined}
        workflowLoading={true}
      />
    );
    expect(screen.queryByText(/loading workflow status/i)).not.toBeInTheDocument();
  });

  describe('LLM accounting', () => {
    it('shows cost, source and session id for a Claude step when expanded', () => {
      renderItem({
        expanded: true,
        execution: makeExecution({
          llmAccounting: { sessionId: 'sess-42', costUsd: 0.1795, source: 'session', fallbackReason: null },
        }),
      });
      expect(screen.getByText('$0.1795')).toBeInTheDocument();
      expect(screen.getByText('proxy')).toBeInTheDocument();
      expect(screen.getByText('sess-42')).toBeInTheDocument();
    });

    it('shows the fallback reason when the lookup fell back', () => {
      renderItem({
        expanded: true,
        execution: makeExecution({
          llmAccounting: { sessionId: 'sess-42', costUsd: 0.5, source: 'cli', fallbackReason: 'http 404' },
        }),
      });
      expect(screen.getByText('CLI estimate')).toBeInTheDocument();
      expect(screen.getByText(/http 404/)).toBeInTheDocument();
    });

    it('shows an incomplete session figure without calling it a fallback', () => {
      renderItem({
        expanded: true,
        execution: makeExecution({
          llmAccounting: {
            sessionId: 'sess-42',
            costUsd: 0.21,
            source: 'session',
            fallbackReason: 'incomplete: 3 of 5 calls ingested',
          },
        }),
      });
      expect(screen.getByText('proxy')).toBeInTheDocument();
      expect(screen.getByText(/incomplete: 3 of 5 calls ingested/)).toBeInTheDocument();
      expect(screen.queryByText(/fallback/)).not.toBeInTheDocument();
    });

    it('renders nothing accounting-related for a non-Claude step', () => {
      renderItem({ expanded: true, execution: makeExecution({ llmAccounting: null }) });
      expect(screen.queryByText(/LLM cost/)).not.toBeInTheDocument();
    });

    it('shows "not reported" instead of $0 for a tokens-only run', () => {
      renderItem({
        expanded: true,
        execution: makeExecution({
          llmAccounting: { sessionId: null, costUsd: 0, source: 'none', fallbackReason: null },
        }),
      });
      expect(screen.getAllByText('not reported')).toHaveLength(2); // cost value + source badge
      expect(screen.queryByText('$0.0000')).not.toBeInTheDocument();
    });

    it('appends token usage, with reasoning tokens when reported', () => {
      renderItem({
        expanded: true,
        execution: makeExecution({
          llmAccounting: {
            sessionId: null,
            costUsd: 0.02,
            source: 'cli',
            fallbackReason: null,
            usage: { input: 1500, output: 400, cacheRead: 0, cacheWrite: 0, reasoning: 40 },
          },
        }),
      });
      expect(screen.getByText(/1,500 in/)).toBeInTheDocument();
      expect(screen.getByText(/400 out/)).toBeInTheDocument();
      expect(screen.getByText(/40 reasoning/)).toBeInTheDocument();
    });

    it('omits the reasoning suffix when reasoning tokens are zero', () => {
      renderItem({
        expanded: true,
        execution: makeExecution({
          llmAccounting: {
            sessionId: null,
            costUsd: 0.02,
            source: 'cli',
            fallbackReason: null,
            usage: { input: 10, output: 5, cacheRead: 0, cacheWrite: 0, reasoning: 0 },
          },
        }),
      });
      expect(screen.getByText(/10 in/)).toBeInTheDocument();
      expect(screen.queryByText(/reasoning/)).not.toBeInTheDocument();
    });
  });

  describe('a11y', () => {
    it('has no violations when expanded with stdout/stderr output tabs', async () => {
      const onToggle = vi.fn();
      const onDownloadArtifact = vi.fn();
      const { container } = render(
        <ExecutionItem
          execution={makeExecution({ stdOut: 'cmd_0.out', stdErr: 'cmd_0.err' })}
          index={0}
          expanded={true}
          onToggle={onToggle}
          processId={42}
          onDownloadArtifact={onDownloadArtifact}
          downloadPending={false}
        />
      );
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});

describe('ExecutionItem memoization', () => {
  it('skips a parent re-render whose props are unchanged', () => {
    // The process detail page re-renders every second for its live timer; an
    // expanded item must not redraw its output tabs on each tick.
    const props = {
      execution: makeExecution(),
      index: 0,
      expanded: true,
      onToggle: vi.fn(),
      processId: 42,
      onDownloadArtifact: vi.fn(),
      downloadPending: false,
    };
    tabsRenders.count = 0;
    const { rerender } = render(<ExecutionItem {...props} />);
    expect(tabsRenders.count).toBe(1);

    rerender(<ExecutionItem {...props} />);
    expect(tabsRenders.count).toBe(1);

    rerender(<ExecutionItem {...props} downloadPending />);
    expect(tabsRenders.count).toBe(2);
  });
});
