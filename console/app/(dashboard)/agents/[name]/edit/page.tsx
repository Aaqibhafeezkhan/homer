'use client';

import { use, useState, useEffect, useCallback } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import {
  useAgent,
  useAgentFiles,
  useAgentFile,
  useSaveAgentFile,
  useDeleteAgentFile,
} from '@/lib/api/queries';
import { HomerApiError } from '@/lib/api/client';
import { Button } from '@/components/ui/button';
import { Save, X, ArrowLeft } from 'lucide-react';
import { FileTree } from '@/components/editor/file-tree';
import { AgentFileEditor } from '@/components/editor/agent-file-editor';
import { DebugCommandPanel } from '@/components/editor/debug-command-panel';
import { useCan } from '@/lib/auth/use-can';
import type { AgentFileEntry, AgentFileListResponse, SchemaErrorResponse } from '@/types/homer';

// Back button, title, unsaved marker and Save.
function EditorHeader({
  agentName,
  dirty,
  canSave,
  saving,
  onBack,
  onSave,
}: {
  agentName: string;
  dirty: boolean;
  canSave: boolean;
  saving: boolean;
  onBack: () => void;
  onSave: () => void;
}) {
  return (
    <div className="flex items-center justify-between border-b border-border p-3">
      <div className="flex items-center gap-3 min-w-0">
        <Button
          variant="ghost"
          size="icon"
          onClick={onBack}
          aria-label="Back to agent"
        >
          <ArrowLeft className="h-4 w-4" aria-hidden="true" />
        </Button>
        <h2 className="text-xl font-semibold truncate">
          Editing <span className="font-mono">{agentName}</span>
        </h2>
        {dirty && (
          <span className="text-xs text-amber-500">● Unsaved</span>
        )}
      </div>
      <Button
        size="sm"
        onClick={onSave}
        disabled={!canSave || !dirty || saving}
      >
        <Save className="mr-2 h-4 w-4" aria-hidden="true" />
        {saving ? 'Saving...' : 'Save'}
      </Button>
    </div>
  );
}

// The last save/delete/create failure and the schema errors of a rejected save.
function SaveErrors({
  saveError,
  schemaErrors,
  onDismissSaveError,
  onDismissSchemaErrors,
}: {
  saveError: string | null;
  schemaErrors: string[] | null;
  onDismissSaveError: () => void;
  onDismissSchemaErrors: () => void;
}) {
  return (
    <>
      {saveError && (
        <div className="border-b border-destructive bg-destructive/10 p-2 text-sm text-destructive flex items-start justify-between gap-2">
          <span>{saveError}</span>
          <button onClick={onDismissSaveError}>
            <X className="h-4 w-4" />
          </button>
        </div>
      )}
      {schemaErrors && (
        <div className="border-b border-destructive bg-destructive/10 p-2 text-sm text-destructive space-y-1">
          <div className="flex items-start justify-between gap-2">
            <span className="font-medium">Schema validation failed:</span>
            <button onClick={onDismissSchemaErrors}>
              <X className="h-4 w-4" />
            </button>
          </div>
          <ul className="list-disc list-inside">
            {schemaErrors.map((err, i) => (
              <li key={i}>{err}</li>
            ))}
          </ul>
        </div>
      )}
    </>
  );
}

// What a failed save shows: a 422's schema errors, or one message line.
function describeSaveFailure(e: unknown): { saveError: string | null; schemaErrors: string[] | null } {
  if (e instanceof HomerApiError && e.status === 422) {
    const body = e.body as Partial<SchemaErrorResponse> | undefined;
    return { saveError: null, schemaErrors: body?.errors ?? [body?.msg ?? 'Schema validation failed'] };
  }
  return { saveError: e instanceof Error ? e.message : 'Save failed', schemaErrors: null };
}

// The file list, or a loading line while it loads.
function FilesPane({
  loading,
  entries,
  activePath,
  onSelect,
  onDelete,
  onNewFile,
}: {
  loading: boolean;
  entries: AgentFileEntry[] | undefined;
  activePath: string | null;
  onSelect: (path: string) => void;
  onDelete: (path: string) => void;
  onNewFile: () => void;
}) {
  if (loading) {
    return <div className="p-3 text-xs text-muted-foreground">Loading files...</div>;
  }
  return (
    <FileTree
      entries={entries ?? []}
      activePath={activePath}
      onSelect={onSelect}
      onDelete={onDelete}
      onNewFile={onNewFile}
    />
  );
}

// The open file's path and editor, or a hint while no file is open.
function EditorPane({
  activePath,
  buffer,
  onChange,
}: {
  activePath: string | null;
  buffer: string;
  onChange: (value: string) => void;
}) {
  if (!activePath) {
    return (
      <div className="flex-1 flex items-center justify-center text-muted-foreground text-sm">
        Select a file to edit
      </div>
    );
  }
  return (
    <>
      <div className="border-b border-border bg-muted/10 px-3 py-1 text-xs font-mono text-muted-foreground">
        {activePath}
      </div>
      <div className="flex-1 min-h-0">
        <AgentFileEditor path={activePath} value={buffer} onChange={onChange} />
      </div>
    </>
  );
}

function NoEditPermission() {
  return (
    <div className="p-6 text-sm text-muted-foreground">
      You don&apos;t have permission to edit this agent.
    </div>
  );
}

function AgentNotFound() {
  return (
    <div className="p-6 space-y-3">
      <h2 className="text-2xl font-bold">Agent not found</h2>
      <Link href="/agents" className="underline">
        Back to agents
      </Link>
    </div>
  );
}

function ReadOnlyAgent({ name, onBack }: { name: string; onBack: () => void }) {
  return (
    <div className="p-6 space-y-3">
      <h2 className="text-2xl font-bold">{name}</h2>
      <div className="rounded-lg border border-amber-500 bg-amber-500/10 p-4 text-sm">
        This agent&apos;s directory is read-only on the server. Editing is
        disabled.
      </div>
      <Button
        variant="outline"
        onClick={onBack}
      >
        <ArrowLeft className="mr-2 h-4 w-4" /> Back
      </Button>
    </div>
  );
}

// The open file, its editor buffer and the error banners.
function useEditorState(agentName: string, filesList: AgentFileListResponse | undefined) {
  const [activePath, setActivePath] = useState<string | null>(null);
  const [buffer, setBuffer] = useState<string>('');
  const [dirty, setDirty] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [schemaErrors, setSchemaErrors] = useState<string[] | null>(null);

  const { data: fileContent } = useAgentFile(agentName, activePath);

  // Auto-select agent.yaml/agent.json on load
  useEffect(() => {
    if (!activePath && filesList?.entries) {
      const preferred =
        filesList.entries.find((f) => f.path === 'agent.yaml') ||
        filesList.entries.find((f) => f.path === 'agent.json');
      // eslint-disable-next-line react-hooks/set-state-in-effect -- initial selection from async-loaded file list
      if (preferred) setActivePath(preferred.path);
    }
  }, [activePath, filesList]);

  // Sync buffer when active file content loads/changes
  useEffect(() => {
    if (fileContent && fileContent.path === activePath) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- sync local editor buffer to async-loaded file content
      setBuffer(fileContent.content);
      setDirty(false);
      setSaveError(null);
      setSchemaErrors(null);
    }
  }, [fileContent, activePath]);

  const handleSelect = useCallback(
    (path: string) => {
      if (dirty && !confirm('Discard unsaved changes?')) return;
      setActivePath(path);
    },
    [dirty],
  );

  const edit = (value: string) => {
    setBuffer(value);
    setDirty(true);
  };

  return {
    activePath, setActivePath, buffer, setBuffer, dirty, setDirty,
    saveError, setSaveError, schemaErrors, setSchemaErrors, handleSelect, edit,
  };
}

type EditorState = ReturnType<typeof useEditorState>;

// Save, delete and create, each reporting its failure into the editor's banners.
function useEditorActions(agentName: string, editor: EditorState) {
  const saveMutation = useSaveAgentFile(agentName);
  const deleteMutation = useDeleteAgentFile(agentName);
  const { activePath, buffer, setActivePath, setBuffer, setDirty, setSaveError, setSchemaErrors } = editor;

  const handleSave = async () => {
    if (!activePath) return;
    setSaveError(null);
    setSchemaErrors(null);
    try {
      await saveMutation.mutateAsync({ path: activePath, content: buffer });
      setDirty(false);
    } catch (e) {
      const failure = describeSaveFailure(e);
      setSaveError(failure.saveError);
      setSchemaErrors(failure.schemaErrors);
    }
  };

  const handleDelete = async (path: string) => {
    if (!confirm(`Delete ${path}? This cannot be undone.`)) return;
    try {
      await deleteMutation.mutateAsync(path);
      if (activePath === path) {
        setActivePath(null);
        setBuffer('');
        setDirty(false);
      }
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Delete failed');
    }
  };

  const handleNewFile = async () => {
    const input = prompt('New file name (relative to agent directory):');
    if (!input) return;
    const path = input.trim();
    if (!path) return;
    try {
      await saveMutation.mutateAsync({ path, content: '' });
      setActivePath(path);
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Create failed');
    }
  };

  return { handleSave, handleDelete, handleNewFile, saving: saveMutation.isPending };
}

export default function AgentEditPage({
  params,
}: {
  params: Promise<{ name: string }>;
}) {
  const { name } = use(params);
  const agentName = decodeURIComponent(name);
  const router = useRouter();

  const { can } = useCan();
  const { data: agent, isLoading: agentLoading } = useAgent(agentName);
  const { data: filesList, isLoading: filesLoading } = useAgentFiles(agentName);
  const editor = useEditorState(agentName, filesList);
  const actions = useEditorActions(agentName, editor);
  const backToAgent = () => router.push(`/agents/${encodeURIComponent(agentName)}`);

  if (!can('edit', agentName)) return <NoEditPermission />;
  if (agentLoading) return <div className="p-6 text-muted-foreground">Loading agent...</div>;
  if (!agent) return <AgentNotFound />;
  if (agent.writable === false) return <ReadOnlyAgent name={agent.name} onBack={backToAgent} />;

  return (
    <div className="flex flex-col h-[calc(100vh-4rem)]">
      <EditorHeader
        agentName={agent.name}
        dirty={editor.dirty}
        canSave={!!editor.activePath}
        saving={actions.saving}
        onBack={backToAgent}
        onSave={actions.handleSave}
      />

      <SaveErrors
        saveError={editor.saveError}
        schemaErrors={editor.schemaErrors}
        onDismissSaveError={() => editor.setSaveError(null)}
        onDismissSchemaErrors={() => editor.setSchemaErrors(null)}
      />

      <div className="flex-1 flex min-h-0">
        <aside className="w-64 border-r border-border bg-muted/20 shrink-0">
          <FilesPane
            loading={filesLoading}
            entries={filesList?.entries}
            activePath={editor.activePath}
            onSelect={editor.handleSelect}
            onDelete={actions.handleDelete}
            onNewFile={actions.handleNewFile}
          />
        </aside>

        <main className="flex-1 min-w-0 flex flex-col">
          <EditorPane activePath={editor.activePath} buffer={editor.buffer} onChange={editor.edit} />
        </main>

        <aside className="w-80 border-l border-border bg-muted/20 shrink-0 overflow-y-auto">
          {can('debug', agentName) && <DebugCommandPanel agentName={agentName} agent={agent} />}
        </aside>
      </div>
    </div>
  );
}
