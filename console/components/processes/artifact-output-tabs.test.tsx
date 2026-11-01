import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { axe } from 'vitest-axe';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ArtifactOutputTabs } from './artifact-output-tabs';
import type { LlmEvent } from '@/types/homer';

// Drive useArtifactContent / useProcessLogStream / useLlmEventStream from the test.
const { artifactContentMock } = vi.hoisted(() => ({
  artifactContentMock: vi.fn(),
}));
vi.mock('@/lib/api/queries', async (orig) => ({
  ...(await orig<typeof import('@/lib/api/queries')>()),
  useArtifactContent: (...args: unknown[]) => artifactContentMock(...args),
}));

const { logStreamMock, llmEventStreamMock } = vi.hoisted(() => ({
  // Typed with a rest param so the `...a` spread in the mock below targets a rest parameter.
  logStreamMock: vi.fn<(...a: unknown[]) => { content: string; offset: number; ended: boolean }>(() => ({
    content: '',
    offset: 0,
    ended: false,
  })),
  llmEventStreamMock: vi.fn<(...a: unknown[]) => { events: LlmEvent[]; ended: boolean }>(() => ({ events: [], ended: false })),
}));
vi.mock('@/lib/api/streams', () => ({
  useProcessLogStream: (...a: unknown[]) => logStreamMock(...a),
  useLlmEventStream: (...a: unknown[]) => llmEventStreamMock(...a),
}));

function renderTabs(overrides: Partial<React.ComponentProps<typeof ArtifactOutputTabs>> = {}) {
  const qc = new QueryClient();
  const onDownload = vi.fn();
  const utils = render(
    <QueryClientProvider client={qc}>
      <ArtifactOutputTabs
        processId={1}
        stdoutPath="cmd_0.out"
        stderrPath="cmd_0.err"
        executionIndex={0}
        defaultTab="stdout"
        commandStart={null}
        onDownload={onDownload}
        downloadPending={false}
        {...overrides}
      />
    </QueryClientProvider>
  );
  return { ...utils, onDownload };
}

// Raw harness NDJSON — no longer sniffed for view-mode detection (that is now
// solely driven by the `kind` prop), but still exercised as opaque raw/log
// content that must load past the "empty content" gate.
const NDJSON =
  '{"type":"system","subtype":"init","model":"claude-sonnet-4-6"}\n' +
  '{"type":"assistant","message":{"content":[{"type":"text","text":"hello from claude"}]}}\n';

describe('ArtifactOutputTabs — post-mortem', () => {
  beforeEach(() => {
    artifactContentMock.mockReset();
    logStreamMock.mockReset();
    logStreamMock.mockReturnValue({ content: '', offset: 0, ended: false });
    llmEventStreamMock.mockReset();
    llmEventStreamMock.mockReturnValue({ events: [], ended: false });
  });

  it('shows a loading state while content is fetching', () => {
    artifactContentMock.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
      refetch: vi.fn(),
    });
    renderTabs();
    expect(screen.getByText('Loading content...')).toBeInTheDocument();
  });

  it('renders raw content when kind is not an LLM kind', async () => {
    artifactContentMock.mockReturnValue({
      data: { content: 'plain text output', filename: 'cmd_0.out', isComplete: true },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });
    renderTabs();
    expect(await screen.findByText('plain text output')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Conversation' })).not.toBeInTheDocument();
  });

  it('renders raw when kind is missing even if the content is NDJSON', async () => {
    artifactContentMock.mockReturnValue({
      data: { content: NDJSON, filename: 'cmd_0.out', isComplete: true },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });
    renderTabs();
    // No kind prop passed — the Conversation/JSON/Raw toggle must not appear,
    // and the harness's own raw lines are shown verbatim rather than parsed.
    expect(await screen.findByText(/"type":"assistant"/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Conversation' })).not.toBeInTheDocument();
  });

  it('renders the conversation view when kind is an LLM kind', async () => {
    artifactContentMock.mockReturnValue({
      data: { content: NDJSON, filename: 'cmd_0.out', isComplete: true },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });
    const events: LlmEvent[] = [
      { type: 'session_start', sessionId: 's', model: 'gpt-5.2-codex', tools: [] },
      { type: 'assistant_message', messageId: 'm1', model: null, text: 'hello from codex', subagent: false },
    ];
    llmEventStreamMock.mockReturnValue({ events, ended: true });
    renderTabs({ kind: 'codex' });
    // The Conversation/JSON/Raw toggle row only appears when the kind is an LLM kind.
    expect(await screen.findByRole('button', { name: 'Conversation' })).toBeInTheDocument();
    expect(screen.getByText('hello from codex')).toBeInTheDocument();
  });

  it('renders the conversation view for an opencode step too', async () => {
    artifactContentMock.mockReturnValue({
      data: { content: NDJSON, filename: 'cmd_0.out', isComplete: true },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });
    const events: LlmEvent[] = [
      { type: 'session_start', sessionId: 'ses_1', model: null, tools: [] },
      { type: 'assistant_message', messageId: 'm1', model: null, text: 'hello from opencode', subagent: false },
    ];
    llmEventStreamMock.mockReturnValue({ events, ended: true });
    renderTabs({ kind: 'opencode' });
    expect(await screen.findByRole('button', { name: 'Conversation' })).toBeInTheDocument();
    expect(screen.getByText('hello from opencode')).toBeInTheDocument();
  });

  it('passes the step accounting into the conversation summary', async () => {
    artifactContentMock.mockReturnValue({
      data: { content: NDJSON, filename: 'cmd_0.out', isComplete: true },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });
    const events: LlmEvent[] = [
      { type: 'session_start', sessionId: 's', model: 'gpt-5.4-mini', tools: [] },
      {
        type: 'turn_end',
        status: { type: 'success' },
        usage: { input: 10, output: 2, cacheRead: 0, cacheWrite: 0, reasoning: 0 },
        costUsd: null,
        model: null,
        durationMs: null,
        durationApiMs: null,
        numTurns: null,
        resultText: null,
      },
    ];
    llmEventStreamMock.mockReturnValue({ events, ended: true });
    renderTabs({ kind: 'codex', accounting: { sessionId: 's', costUsd: 0.008, source: 'session', fallbackReason: null } });
    expect(await screen.findByText('$0.0080')).toBeInTheDocument();
    expect(screen.queryByText('not reported')).not.toBeInTheDocument();
  });

  it('renders raw stderr text (not the conversation view) for an LLM-kind step, and restores the conversation view on switching back to stdout', async () => {
    artifactContentMock.mockImplementation((_processId: number, path: string | null) => ({
      data: {
        content: path === 'cmd_0.out' ? NDJSON : 'stderr error text',
        filename: path,
        isComplete: true,
      },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    }));
    const events: LlmEvent[] = [
      { type: 'assistant_message', messageId: 'm1', model: null, text: 'hello from codex', subagent: false },
    ];
    llmEventStreamMock.mockReturnValue({ events, ended: true });
    renderTabs({ kind: 'codex' });

    expect(await screen.findByRole('button', { name: 'Conversation' })).toBeInTheDocument();
    expect(screen.getByText('hello from codex')).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByRole('tab', { name: 'Stderr' }));

    // Stderr is always raw text for an LLM-kind step — no Conversation/JSON/Raw
    // toggle, and no stale conversation content from the stdout-only hook.
    expect(await screen.findByText('stderr error text')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Conversation' })).not.toBeInTheDocument();
    expect(screen.queryByText('hello from codex')).not.toBeInTheDocument();
    expect(screen.queryByTestId('live-llm-view')).not.toBeInTheDocument();

    await user.click(screen.getByRole('tab', { name: 'Stdout' }));

    expect(await screen.findByRole('button', { name: 'Conversation' })).toBeInTheDocument();
    expect(screen.getByText('hello from codex')).toBeInTheDocument();
  });

  it('switches tabs and fetches the stderr artifact', async () => {
    artifactContentMock.mockImplementation((_processId: number, path: string | null) => ({
      data: { content: `content for ${path}`, filename: path, isComplete: true },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    }));
    renderTabs();
    expect(await screen.findByText('content for cmd_0.out')).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(screen.getByRole('tab', { name: 'Stderr' }));

    expect(await screen.findByText('content for cmd_0.err')).toBeInTheDocument();
  });

  it('has no accessibility violations with content loaded', async () => {
    artifactContentMock.mockReturnValue({
      data: { content: 'plain text output', filename: 'cmd_0.out', isComplete: true },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });
    const { container } = renderTabs();
    expect(await screen.findByText('plain text output')).toBeInTheDocument();
    expect(await axe(container)).toHaveNoViolations();
  });
});

describe('ArtifactOutputTabs — live LLM stream', () => {
  beforeEach(() => {
    artifactContentMock.mockReset();
    logStreamMock.mockReset();
    logStreamMock.mockReturnValue({ content: '', offset: 0, ended: false });
    llmEventStreamMock.mockReset();
    llmEventStreamMock.mockReturnValue({ events: [], ended: false });
  });

  // While streaming (isComplete false), the component renders the conversation
  // view from the canonical /llm-events stream, not the raw log tail.
  const streamingArtifact = {
    data: { content: '', filename: 'cmd_0.out', isComplete: false },
    isLoading: false,
    isError: false,
    refetch: vi.fn(),
  };

  it('renders the live conversation view from /llm-events while streaming', async () => {
    artifactContentMock.mockReturnValue(streamingArtifact);
    logStreamMock.mockReturnValue({ content: 'raw harness line\n', offset: 18, ended: false });
    llmEventStreamMock.mockReturnValue({
      events: [
        { type: 'session_start', sessionId: 's', model: 'claude-sonnet-4-6', tools: [] },
        { type: 'assistant_message', messageId: 'm1', model: null, text: 'streaming hi', subagent: false },
      ] satisfies LlmEvent[],
      ended: false,
    });
    renderTabs({ kind: 'claude' });
    expect(await screen.findByTestId('live-llm-view')).toBeInTheDocument();
    expect(screen.getByText('streaming hi')).toBeInTheDocument();
  });

  it('shows the Live badge while streaming', async () => {
    artifactContentMock.mockReturnValue(streamingArtifact);
    logStreamMock.mockReturnValue({ content: 'raw harness line\n', offset: 18, ended: false });
    llmEventStreamMock.mockReturnValue({
      events: [
        { type: 'session_start', sessionId: 's', model: 'claude-sonnet-4-6', tools: [] },
        { type: 'assistant_message', messageId: 'm1', model: null, text: 'streaming hi', subagent: false },
      ] satisfies LlmEvent[],
      ended: false,
    });
    renderTabs({ kind: 'claude' });
    expect(await screen.findByTestId('live-llm-view')).toBeInTheDocument();
    expect(screen.getAllByText('Live').length).toBeGreaterThan(0);
  });

  it('keeps the Conversation/JSON/Raw toggles available while streaming (regression)', async () => {
    // The live view must share the post-mortem layout: the view-mode toggles
    // were dropped when the live branch rendered its own minimal header.
    artifactContentMock.mockReturnValue(streamingArtifact);
    logStreamMock.mockReturnValue({ content: 'raw harness line\n', offset: 18, ended: false });
    llmEventStreamMock.mockReturnValue({
      events: [{ type: 'assistant_message', messageId: 'm1', model: null, text: 'streaming hi', subagent: false }],
      ended: false,
    });
    renderTabs({ kind: 'claude' });
    expect(await screen.findByTestId('live-llm-view')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Conversation' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'JSON' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Raw' })).toBeInTheDocument();
  });

  it('shows intermediate events live, not only the final turn end (regression)', async () => {
    // The full replayable /llm-events stream (not the old broadcast-only
    // event feed) must carry the early assistant turn + tool call, not
    // only the terminal turn_end.
    artifactContentMock.mockReturnValue(streamingArtifact);
    logStreamMock.mockReturnValue({ content: 'raw harness line\n', offset: 18, ended: false });
    llmEventStreamMock.mockReturnValue({
      events: [
        { type: 'session_start', sessionId: 's', model: 'claude-sonnet-4-6', tools: [] },
        { type: 'assistant_message', messageId: 'm1', model: null, text: "I'll search for stuck tickets.", subagent: false },
        { type: 'tool_call', id: 't1', name: 'mcp__youtrack__search_issues', input: { query: 'x' }, messageId: 'm2' },
      ] satisfies LlmEvent[],
      ended: false,
    });
    renderTabs({ kind: 'claude' });
    expect(await screen.findByTestId('live-llm-view')).toBeInTheDocument();
    expect(screen.getByText(/I'll search for stuck tickets/)).toBeInTheDocument();
  });

  it('falls back to raw log tail when kind is not an LLM kind', async () => {
    artifactContentMock.mockReturnValue(streamingArtifact);
    logStreamMock.mockReturnValue({ content: 'plain log output', offset: 16, ended: false });
    renderTabs();
    expect(await screen.findByText('plain log output')).toBeInTheDocument();
    expect(screen.queryByTestId('live-llm-view')).not.toBeInTheDocument();
  });
});

describe('ArtifactOutputTabs — download button', () => {
  beforeEach(() => {
    artifactContentMock.mockReset();
    artifactContentMock.mockReturnValue({
      data: { content: 'plain output', filename: 'cmd_0.out', isComplete: true },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });
    logStreamMock.mockReset();
    logStreamMock.mockReturnValue({ content: '', offset: 0, ended: false });
    llmEventStreamMock.mockReset();
    llmEventStreamMock.mockReturnValue({ events: [], ended: false });
  });

  it('shows the active tab filename and calls onDownload with its path', async () => {
    const { onDownload } = renderTabs();
    const downloadButton = await screen.findByRole('button', { name: /cmd_0\.out/ });
    expect(downloadButton).toBeInTheDocument();

    const user = userEvent.setup();
    await user.click(downloadButton);

    expect(onDownload).toHaveBeenCalledWith('cmd_0.out');
  });

  it('hides the download button when the active tab path is null', async () => {
    renderTabs({ stdoutPath: null });
    // Content is loaded (this block's beforeEach sets isLoading: false with
    // data present), but the stdout path itself is null, so no filename/button
    // can be derived from it.
    expect(screen.queryByRole('button', { name: /cmd_0\.out/ })).not.toBeInTheDocument();
  });

  it('disables the download button while a download is pending', async () => {
    renderTabs({ downloadPending: true });
    const downloadButton = await screen.findByRole('button', { name: /cmd_0\.out/ });
    expect(downloadButton).toBeDisabled();
  });
});

describe('ArtifactOutputTabs — enabled gate', () => {
  beforeEach(() => {
    artifactContentMock.mockReset();
    artifactContentMock.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });
    logStreamMock.mockReset();
    logStreamMock.mockReturnValue({ content: '', offset: 0, ended: false });
    llmEventStreamMock.mockReset();
    llmEventStreamMock.mockReturnValue({ events: [], ended: false });
  });

  it('passes enabled=false through to useArtifactContent when disabled', () => {
    renderTabs({ enabled: false });
    // useArtifactContent(processId, path, enabled) — the third arg reflects our `enabled` prop.
    expect(artifactContentMock).toHaveBeenCalledWith(1, 'cmd_0.out', false);
  });
});

describe('ArtifactOutputTabs — content states and view modes', () => {
  beforeEach(() => {
    artifactContentMock.mockReset();
    logStreamMock.mockReset();
    logStreamMock.mockReturnValue({ content: '', offset: 0, ended: false });
    llmEventStreamMock.mockReset();
    llmEventStreamMock.mockReturnValue({ events: [], ended: false });
  });

  function content(text: string, isComplete = true) {
    artifactContentMock.mockReturnValue({
      data: { content: text, filename: 'cmd_0.out', isComplete },
      isLoading: false,
      isError: false,
      refetch: vi.fn(),
    });
  }

  it('says so when the active tab has no artifact', () => {
    content('ignored');
    renderTabs({ stdoutPath: null });
    expect(screen.getByText('No stdout available')).toBeInTheDocument();
  });

  it('shows the load error, or a generic one without a message', () => {
    artifactContentMock.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: new Error('boom'),
      refetch: vi.fn(),
    });
    const { unmount } = renderTabs();
    expect(screen.getByText('Failed to load content: boom')).toBeInTheDocument();
    unmount();

    artifactContentMock.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      error: null,
      refetch: vi.fn(),
    });
    renderTabs();
    expect(screen.getByText('Failed to load content: Unknown error')).toBeInTheDocument();
  });

  it('distinguishes an empty finished file from a stream awaiting output', () => {
    content('');
    const { unmount } = renderTabs();
    expect(screen.getByText('File is empty')).toBeInTheDocument();
    unmount();

    content('', false);
    renderTabs();
    expect(screen.getByText('Waiting for output…')).toBeInTheDocument();
  });

  it('shows the REST snapshot until the log stream delivered a chunk', () => {
    content('snapshot text', false);
    logStreamMock.mockReturnValue({ content: 'tail text', offset: 0, ended: false });
    renderTabs();
    expect(screen.getByText('snapshot text')).toBeInTheDocument();
    expect(screen.queryByText('tail text')).not.toBeInTheDocument();
  });

  it('renders parsed NDJSON lines in the JSON mode, skipping malformed lines', async () => {
    const user = userEvent.setup();
    content('{"answer":42}\n\nnot json\n{"tool":"grep"}\n');
    renderTabs({ kind: 'claude' });
    await user.click(screen.getByRole('button', { name: 'JSON' }));
    expect(screen.getByText('42')).toBeInTheDocument();
    expect(screen.getByText(/grep/)).toBeInTheDocument();
    expect(screen.queryByText(/not json/)).not.toBeInTheDocument();
  });

  it('switches an LLM step to the raw view and back to the conversation', async () => {
    const user = userEvent.setup();
    content('raw harness bytes');
    llmEventStreamMock.mockReturnValue({
      events: [{ type: 'assistant_message', messageId: 'm1', model: null, text: 'hi there', subagent: false }],
      ended: true,
    });
    renderTabs({ kind: 'claude' });
    expect(screen.getByText('hi there')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Raw' }));
    expect(screen.getByText('raw harness bytes')).toBeInTheDocument();
    expect(screen.queryByText('hi there')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Conversation' }));
    expect(screen.getByText('hi there')).toBeInTheDocument();
  });
});
