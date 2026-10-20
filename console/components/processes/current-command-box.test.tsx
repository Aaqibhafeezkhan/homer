import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { CurrentCommandBox } from './current-command-box';
import type { LangGraphRunStatus } from '@/types/homer';

vi.mock('./workflow-status', () => ({
  WorkflowStatus: () => <div data-testid="workflow-status" />,
}));

vi.mock('./artifact-output-tabs', () => ({
  ArtifactOutputTabs: (props: Record<string, unknown>) => (
    <div data-testid="artifact-output-tabs" data-props={JSON.stringify(props)} />
  ),
}));

function makeWorkflow(): LangGraphRunStatus {
  return {
    label: 'flow',
    module: 'm:graph',
    threadId: 't1',
    topology: { nodes: [{ id: 'a' }], edges: [] },
    nodes: { a: 'done' },
    errors: null,
  };
}

function renderBox(props: Partial<React.ComponentProps<typeof CurrentCommandBox>> = {}) {
  const onDownloadArtifact = vi.fn();
  const result = render(
    <CurrentCommandBox
      command="deploy-eu"
      commandStart={1_700_000_000}
      executionsLength={2}
      now={1_700_000_010}
      processId={42}
      onDownloadArtifact={onDownloadArtifact}
      downloadPending={false}
      {...props}
    />
  );
  return { ...result, onDownloadArtifact };
}

describe('CurrentCommandBox', () => {
  it('renders the command name and running indicator', () => {
    renderBox();
    expect(screen.getByText('deploy-eu')).toBeInTheDocument();
    expect(screen.getByText('Running...')).toBeInTheDocument();
  });

  it('is collapsed by default and does not render the output tabs', () => {
    renderBox();
    expect(screen.queryByTestId('artifact-output-tabs')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Show output')).toBeInTheDocument();
  });

  it('toggles the output tabs open and closed, passing cmd_N paths for the next execution', () => {
    renderBox({ executionsLength: 3 });

    fireEvent.click(screen.getByLabelText('Show output'));
    const tabs = screen.getByTestId('artifact-output-tabs');
    const props = JSON.parse(tabs.getAttribute('data-props') ?? '{}');
    expect(props).toMatchObject({
      processId: 42,
      stdoutPath: 'cmd_3.out',
      stderrPath: 'cmd_3.err',
      executionIndex: 3,
      enabled: true,
      commandStart: 1_700_000_000,
      downloadPending: false,
    });
    expect(screen.getByLabelText('Hide output')).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText('Hide output'));
    expect(screen.queryByTestId('artifact-output-tabs')).not.toBeInTheDocument();
  });

  it('uses currentCommandIndex (falling back to executionsLength when null) for the cmd_N paths, and passes commandKind as kind', () => {
    renderBox({ executionsLength: 3, currentCommandIndex: 5, commandKind: 'codex' });

    fireEvent.click(screen.getByLabelText('Show output'));
    const tabs = screen.getByTestId('artifact-output-tabs');
    const props = JSON.parse(tabs.getAttribute('data-props') ?? '{}');
    expect(props).toMatchObject({
      stdoutPath: 'cmd_5.out',
      stderrPath: 'cmd_5.err',
      executionIndex: 5,
      kind: 'codex',
    });
  });

  it('falls back to executionsLength when currentCommandIndex is null', () => {
    renderBox({ executionsLength: 3, currentCommandIndex: null });

    fireEvent.click(screen.getByLabelText('Show output'));
    const tabs = screen.getByTestId('artifact-output-tabs');
    const props = JSON.parse(tabs.getAttribute('data-props') ?? '{}');
    expect(props).toMatchObject({ stdoutPath: 'cmd_3.out', executionIndex: 3 });
  });

  it('omits timing block when commandStart is null', () => {
    const { container } = renderBox({ commandStart: null, executionsLength: 0 });
    // the right-hand timing div should have no children
    const timing = container.querySelector('.text-right');
    expect(timing?.children.length).toBe(0);
  });

  it('hides the workflow toggle unless showWorkflowToggle is set', () => {
    renderBox({ command: 'flow', executionsLength: 0 });
    expect(screen.queryByLabelText(/show workflow/i)).not.toBeInTheDocument();
  });

  it('fires onToggleWorkflow and flips the toggle label when expanded', () => {
    const onToggleWorkflow = vi.fn();
    const { rerender } = renderBox({
      command: 'flow',
      executionsLength: 0,
      showWorkflowToggle: true,
      workflowExpanded: false,
      onToggleWorkflow,
    });
    fireEvent.click(screen.getByLabelText('Show workflow'));
    expect(onToggleWorkflow).toHaveBeenCalledTimes(1);

    rerender(
      <CurrentCommandBox
        command="flow"
        commandStart={1_700_000_000}
        executionsLength={0}
        now={1_700_000_010}
        processId={42}
        onDownloadArtifact={vi.fn()}
        downloadPending={false}
        showWorkflowToggle={true}
        workflowExpanded={true}
        onToggleWorkflow={onToggleWorkflow}
      />
    );
    expect(screen.getByLabelText('Hide workflow')).toBeInTheDocument();
  });

  it('renders the workflow when expanded, a loading hint while fetching, and nothing when collapsed', () => {
    const { rerender } = renderBox({
      command: 'flow',
      executionsLength: 0,
      showWorkflowToggle: true,
      workflowExpanded: true,
      onToggleWorkflow: vi.fn(),
      workflow: undefined,
      workflowLoading: true,
    });
    expect(screen.getByText(/loading workflow status/i)).toBeInTheDocument();

    rerender(
      <CurrentCommandBox
        command="flow"
        commandStart={1_700_000_000}
        executionsLength={0}
        now={1_700_000_010}
        processId={42}
        onDownloadArtifact={vi.fn()}
        downloadPending={false}
        showWorkflowToggle={true}
        workflowExpanded={true}
        onToggleWorkflow={vi.fn()}
        workflow={makeWorkflow()}
        workflowLoading={false}
      />
    );
    expect(screen.getByTestId('workflow-status')).toBeInTheDocument();

    rerender(
      <CurrentCommandBox
        command="flow"
        commandStart={1_700_000_000}
        executionsLength={0}
        now={1_700_000_010}
        processId={42}
        onDownloadArtifact={vi.fn()}
        downloadPending={false}
        showWorkflowToggle={true}
        workflowExpanded={false}
        onToggleWorkflow={vi.fn()}
        workflow={makeWorkflow()}
        workflowLoading={false}
      />
    );
    expect(screen.queryByTestId('workflow-status')).not.toBeInTheDocument();
    expect(screen.queryByText(/loading workflow status/i)).not.toBeInTheDocument();
  });

  describe('a11y', () => {
    it('has no violations for a running command', async () => {
      const { container } = renderBox({ command: 'deploy', executionsLength: 1 });
      expect(await axe(container)).toHaveNoViolations();
    });

    it('has no violations with the output tabs expanded', async () => {
      const { container } = renderBox({ command: 'deploy', executionsLength: 1 });
      fireEvent.click(screen.getByLabelText('Show output'));
      expect(await axe(container)).toHaveNoViolations();
    });
  });
});
