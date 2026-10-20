'use client';

import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Download, RefreshCw } from 'lucide-react';
import { memo, useEffect, useMemo, useState } from 'react';
import { useArtifactContent } from '@/lib/api/queries';
import { useProcessLogStream, useLlmEventStream, type LlmEventStreamState } from '@/lib/api/streams';
import { extractFilename, toArtifactApiPath } from '@/lib/utils/download';
import { JsonView, allExpanded } from 'react-json-view-lite';
import { LlmConversationView } from './llm-conversation-view';
import { isLlmKind } from '@/types/homer';
import type { LlmAccounting, LlmEvent } from '@/types/homer';

interface ArtifactOutputTabsProps {
  processId: number;
  stdoutPath: string | null;
  stderrPath: string | null;
  executionIndex: number;
  defaultTab?: 'stdout' | 'stderr';
  commandStart?: number | null;
  enabled?: boolean;
  // Command tag; gates the Conversation view (`isLlmKind`) for `!<claude>` / `!<codex>`
  // steps — only on the stdout tab (stderr always reads as plain raw text).
  kind?: string | null;
  // The step's resolved LLM cost, shown in the Conversation view's summary.
  accounting?: LlmAccounting | null;
  onDownload: (artifactPath: string | null) => void;
  downloadPending: boolean;
}

const jsonStyles = {
  container: 'text-xs font-mono',
  basicChildStyle: 'ml-4 my-0.5',
  /* eslint-disable no-restricted-syntax -- syntax colours: no token gives the same light/dark pair */
  label: 'text-blue-600 dark:text-blue-400 mr-1',
  nullValue: 'text-gray-400 dark:text-gray-500',
  undefinedValue: 'text-gray-400 dark:text-gray-500',
  numberValue: 'text-emerald-600 dark:text-emerald-400',
  stringValue: 'text-orange-600 dark:text-orange-300',
  booleanValue: 'text-violet-600 dark:text-violet-400',
  /* eslint-enable no-restricted-syntax */
  otherValue: 'text-muted-foreground',
  punctuation: 'text-foreground',
  expandIcon: 'cursor-pointer select-none mr-1 text-muted-foreground',
  collapseIcon: 'cursor-pointer select-none mr-1 text-muted-foreground',
  pointerCursor: 'cursor-pointer',
};

type ViewMode = 'conversation' | 'tree' | 'raw';

// ── Render-mode bodies ─────────────────────────────────────────────────────────

function ConversationMode({
  events,
  live,
  commandStart,
  truncated,
  accounting,
}: {
  events: LlmEvent[];
  live: boolean;
  commandStart?: number | null;
  truncated: boolean;
  accounting: LlmAccounting | null;
}) {
  return (
    <LlmConversationView
      events={events}
      live={live}
      commandStart={commandStart}
      truncated={truncated}
      accounting={accounting}
    />
  );
}

/** Best-effort: one JSON value per line of the harness's own raw output; a malformed/partial line is skipped. */
function parseRawJsonLines(content: string): unknown[] {
  const parsed: unknown[] = [];
  for (const line of content.split('\n')) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    try {
      parsed.push(JSON.parse(trimmed));
    } catch {
      // malformed or partial line — skip
    }
  }
  return parsed;
}

function TreeMode({ content }: { content: string }) {
  const data = useMemo(() => parseRawJsonLines(content), [content]);
  return <JsonView data={data} shouldExpandNode={allExpanded} style={jsonStyles} />;
}

function RawMode({ content }: { content: string }) {
  return (
    <pre className="text-xs font-mono whitespace-pre-wrap break-all">
      {content}
    </pre>
  );
}

type ArtifactQuery = ReturnType<typeof useArtifactContent>;

/** The message shown instead of the content: no artifact, loading, failed, or nothing to show yet. */
function contentPlaceholder(
  activeTab: 'stdout' | 'stderr',
  currentPath: string | null,
  query: ArtifactQuery,
  content: string,
  isStreaming: boolean,
): React.ReactNode {
  if (!currentPath) {
    return <div className="text-muted-foreground text-sm">No {activeTab} available</div>;
  }

  if (query.isLoading) {
    return <div className="text-muted-foreground text-sm">Loading content...</div>;
  }

  if (query.isError) {
    return (
      <div className="text-destructive text-sm">
        Failed to load content: {query.error?.message || 'Unknown error'}
      </div>
    );
  }

  if (!content) {
    return (
      <div className="text-muted-foreground text-sm">
        {isStreaming ? 'Waiting for output…' : 'File is empty'}
      </div>
    );
  }

  return null;
}

const MODE_BUTTONS: ReadonlyArray<[ViewMode, string]> = [
  ['conversation', 'Conversation'],
  ['tree', 'JSON'],
  ['raw', 'Raw'],
];

function ModeToggle({ mode, onSelect }: { mode: ViewMode; onSelect: (mode: ViewMode) => void }) {
  return (
    <>
      {MODE_BUTTONS.map(([value, label]) => (
        <Button
          key={value}
          variant={mode === value ? 'default' : 'outline'}
          size="sm"
          onClick={() => onSelect(value)}
        >
          {label}
        </Button>
      ))}
    </>
  );
}

/** The raw-content body: the JSON tree for `tree`, the verbatim text otherwise. */
function TextMode({ mode, content }: { mode: ViewMode; content: string }) {
  return mode === 'tree' ? <TreeMode content={content} /> : <RawMode content={content} />;
}

/** The conversation of an LLM step in `conversation` mode, its text otherwise. */
function ContentBody({
  isLlm,
  mode,
  content,
  llm,
  streaming,
  commandStart,
  accounting,
}: {
  isLlm: boolean;
  mode: ViewMode;
  content: string;
  llm: LlmEventStreamState;
  streaming: boolean;
  commandStart: number | null;
  accounting: LlmAccounting | null;
}) {
  return isLlm && mode === 'conversation' ? (
    <ConversationMode
      events={llm.events}
      live={streaming && !llm.ended}
      commandStart={commandStart}
      truncated={llm.truncated}
      accounting={accounting}
    />
  ) : (
    <TextMode mode={isLlm ? mode : 'raw'} content={content} />
  );
}

/** The picked view mode, else the default: the conversation for an LLM step, raw text otherwise. */
function effectiveViewMode(viewMode: ViewMode | null, isLlm: boolean): ViewMode {
  return viewMode ?? (isLlm ? 'conversation' : 'raw');
}

/** The file name, the Live badge while the file still grows, and an LLM step's mode toggle. */
function ContentHeader({
  data,
  isLlm,
  mode,
  onSelect,
}: {
  data: ArtifactQuery['data'];
  isLlm: boolean;
  mode: ViewMode;
  onSelect: (mode: ViewMode) => void;
}) {
  return (
    <div className="flex-none flex items-center gap-2">
      <span className="text-xs font-mono text-muted-foreground">
        {data?.filename}
      </span>
      {data?.isComplete === false && (
        <Badge variant="secondary" className="text-xs">Live</Badge>
      )}
      {isLlm && <ModeToggle mode={mode} onSelect={onSelect} />}
    </div>
  );
}

/** The active tab's artifact query and path; only the active tab's query is enabled. */
function useActiveArtifact(
  processId: number,
  stdoutPath: string | null,
  stderrPath: string | null,
  activeTab: 'stdout' | 'stderr',
  enabled: boolean,
): { currentQuery: ArtifactQuery; currentPath: string | null } {
  const stdoutQuery = useArtifactContent(
    processId,
    stdoutPath ? toArtifactApiPath(stdoutPath) : null,
    enabled && activeTab === 'stdout'
  );

  const stderrQuery = useArtifactContent(
    processId,
    stderrPath ? toArtifactApiPath(stderrPath) : null,
    enabled && activeTab === 'stderr'
  );

  return activeTab === 'stdout'
    ? { currentQuery: stdoutQuery, currentPath: stdoutPath }
    : { currentQuery: stderrQuery, currentPath: stderrPath };
}

/** The text to show: the REST snapshot, or the SSE tail once it has delivered a chunk. */
function shownContent(
  data: ArtifactQuery['data'],
  isStreaming: boolean,
  logStream: { offset: number; content: string },
): string {
  const baseContent = data?.content ?? '';
  // While the file is still being written, both endpoints serve the same
  // bytes: REST returns the snapshot, SSE returns from byte 0 and tails.
  // Concatenating them duplicates the existing content. Once SSE has
  // delivered any chunk we switch to its buffer (which carries the live
  // tail too); until then we show the REST snapshot so the dialog isn't
  // empty during the brief SSE handshake.
  return isStreaming && logStream.offset > 0 ? logStream.content : baseContent;
}

type OutputTab = 'stdout' | 'stderr';

/** The tab, mode and stream state behind the tabs: queries, the log tail and the LLM event stream. */
function useArtifactOutput({
  processId,
  stdoutPath,
  stderrPath,
  executionIndex,
  defaultTab,
  enabled,
  kind,
}: {
  processId: number;
  stdoutPath: string | null;
  stderrPath: string | null;
  executionIndex: number;
  defaultTab: OutputTab;
  enabled: boolean;
  kind: string | null;
}) {
  const [activeTab, setActiveTab] = useState(defaultTab);
  const [viewMode, setViewMode] = useState<ViewMode | null>(null);
  // The Conversation view only exists for stdout — an LLM harness's stderr is
  // always plain text, so `isLlm` is scoped to the stdout tab and every mode
  // gate (toggle row, effectiveMode default, render branch, the llm hook and
  // liveView below) derives from this one flag rather than re-deriving the
  // tab check ad hoc.
  const isLlm = isLlmKind(kind) && activeTab === 'stdout';

  const { currentQuery, currentPath } = useActiveArtifact(
    processId,
    stdoutPath,
    stderrPath,
    activeTab,
    enabled,
  );
  const isStreaming = enabled && currentQuery.data?.isComplete === false && !!currentPath;
  const logStream = useProcessLogStream(
    processId,
    executionIndex,
    activeTab === 'stdout' ? 'out' : 'err',
    isStreaming
  );
  const llm = useLlmEventStream(processId, executionIndex, enabled && isLlm);

  // Reset view mode when the component becomes enabled (e.g. dialog opens)
  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- intentional state reset when enabled transitions to true
    if (enabled) setViewMode(null);
  }, [enabled]);

  const content = shownContent(currentQuery.data, isStreaming, logStream);

  const selectTab = (tab: OutputTab) => {
    setActiveTab(tab);
    setViewMode(null);
  };

  return { activeTab, selectTab, viewMode, setViewMode, isLlm, currentQuery, currentPath, isStreaming, content, llm };
}

/** The tab list, the artifact's download link and the refresh button. */
function OutputToolbar({
  currentPath,
  currentQuery,
  onDownload,
  downloadPending,
}: {
  currentPath: string | null;
  currentQuery: ArtifactQuery;
  onDownload: (artifactPath: string | null) => void;
  downloadPending: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-2">
      <TabsList>
        <TabsTrigger value="stdout">Stdout</TabsTrigger>
        <TabsTrigger value="stderr">Stderr</TabsTrigger>
      </TabsList>

      <div className="flex items-center gap-1">
        {currentPath && (
          <Button
            variant="link"
            size="sm"
            className="h-auto p-0 font-mono text-muted-foreground hover:text-foreground"
            onClick={() => onDownload(currentPath)}
            disabled={downloadPending}
          >
            {extractFilename(currentPath)}
            <Download className="ml-1 h-3 w-3" />
          </Button>
        )}
        <Button
          variant="ghost"
          size="sm"
          aria-label="Refresh"
          onClick={() => currentQuery.refetch()}
          disabled={currentQuery.isLoading || !currentPath}
        >
          <RefreshCw className="h-4 w-4" />
        </Button>
      </div>
    </div>
  );
}

/** The active tab's body: a placeholder, or the header above the content in the picked mode. */
function OutputContent({
  output,
  commandStart,
  accounting,
}: {
  output: ReturnType<typeof useArtifactOutput>;
  commandStart: number | null;
  accounting: LlmAccounting | null;
}) {
  const { activeTab, currentPath, currentQuery, content, isStreaming, isLlm, viewMode } = output;
  const placeholder = contentPlaceholder(activeTab, currentPath, currentQuery, content, isStreaming);
  if (placeholder) return placeholder;

  // While the command runs, the conversation view is driven by the
  // canonical, replayable /llm-events stream (which replaced the old
  // broadcast-only event feed); tree/raw keep reading the raw
  // artifact/log-tail content regardless of kind. The live path shares
  // the post-mortem layout so the Conversation/JSON/Raw toggles stay
  // available mid-run; only the Live badge and the conversation view's
  // live ticker differ.
  const liveView = isStreaming && isLlm;
  const effectiveMode = effectiveViewMode(viewMode, isLlm);

  return (
    <div
      data-testid={liveView ? 'live-llm-view' : undefined}
      className="flex flex-col min-h-0 flex-1 gap-2"
    >
      <ContentHeader
        data={currentQuery.data}
        isLlm={isLlm}
        mode={effectiveMode}
        onSelect={output.setViewMode}
      />
      <div className="max-h-[60vh] overflow-auto rounded border bg-muted/50 p-4">
        <ContentBody
          isLlm={isLlm}
          mode={effectiveMode}
          content={content}
          llm={output.llm}
          streaming={isStreaming}
          commandStart={commandStart}
          accounting={accounting}
        />
      </div>
    </div>
  );
}

export const ArtifactOutputTabs = memo(function ArtifactOutputTabs({
  processId,
  stdoutPath,
  stderrPath,
  executionIndex,
  defaultTab = 'stdout',
  commandStart = null,
  enabled = true,
  kind = null,
  accounting = null,
  onDownload,
  downloadPending,
}: ArtifactOutputTabsProps) {
  const output = useArtifactOutput({
    processId,
    stdoutPath,
    stderrPath,
    executionIndex,
    defaultTab,
    enabled,
    kind,
  });
  const body = <OutputContent output={output} commandStart={commandStart} accounting={accounting} />;

  return (
    <Tabs value={output.activeTab} onValueChange={(v) => output.selectTab(v as OutputTab)}>
      <OutputToolbar
        currentPath={output.currentPath}
        currentQuery={output.currentQuery}
        onDownload={onDownload}
        downloadPending={downloadPending}
      />

      <TabsContent value="stdout" className="mt-4 flex flex-col">
        {body}
      </TabsContent>

      <TabsContent value="stderr" className="mt-4 flex flex-col">
        {body}
      </TabsContent>
    </Tabs>
  );
});
