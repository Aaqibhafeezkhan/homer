'use client';

import type { AgentFileEntry } from '@/types/homer';
import { FileText, FolderOpen, Trash2, Plus } from 'lucide-react';
import { Button } from '@/components/ui/button';

interface FileTreeProps {
  entries: AgentFileEntry[];
  activePath: string | null;
  onSelect: (path: string) => void;
  onDelete?: (path: string) => void;
  onNewFile?: () => void;
}

export function FileTree({
  entries,
  activePath,
  onSelect,
  onDelete,
  onNewFile,
}: FileTreeProps) {
  const sorted = [...entries].sort((a, b) => {
    if (a.isDirectory !== b.isDirectory) return a.isDirectory ? -1 : 1;
    return a.path.localeCompare(b.path);
  });

  return (
    <div className="flex flex-col h-full">
      <div className="flex items-center justify-between p-2 border-b border-border">
        <span className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
          Files
        </span>
        {onNewFile && (
          <Button
            size="sm"
            variant="ghost"
            className="h-7 w-7 p-0"
            onClick={onNewFile}
            aria-label="New file"
          >
            <Plus className="h-4 w-4" />
          </Button>
        )}
      </div>
      <ul className="flex-1 overflow-y-auto p-1" role="tree">
        {sorted.length === 0 && (
          <li className="text-xs text-muted-foreground p-2">No files</li>
        )}
        {sorted.map((entry) => (
          <FileTreeItem
            key={entry.path}
            entry={entry}
            active={!entry.isDirectory && entry.path === activePath}
            onSelect={onSelect}
            onDelete={onDelete}
          />
        ))}
      </ul>
    </div>
  );
}

/** The agent manifest files; the tree never offers to delete them. */
const PROTECTED_PATHS: ReadonlySet<string> = new Set(['agent.yaml', 'agent.json']);

function FileTreeItem({
  entry,
  active,
  onSelect,
  onDelete,
}: {
  entry: AgentFileEntry;
  active: boolean;
  onSelect: (path: string) => void;
  onDelete?: (path: string) => void;
}) {
  return (
    <li
      role="treeitem"
      aria-selected={active}
      tabIndex={entry.isDirectory ? -1 : 0}
      className={`group flex items-center justify-between gap-1 rounded px-2 py-1 text-sm cursor-pointer ${
        active
          ? 'bg-accent text-accent-foreground'
          : 'hover:bg-accent/50'
      }`}
      onClick={() => !entry.isDirectory && onSelect(entry.path)}
      onKeyDown={(e) => {
        if (!entry.isDirectory && (e.key === 'Enter' || e.key === ' ')) {
          e.preventDefault();
          onSelect(entry.path);
        }
      }}
    >
      <span className="flex items-center gap-2 min-w-0 flex-1">
        {entry.isDirectory ? (
          <FolderOpen
            className="h-4 w-4 shrink-0 text-muted-foreground"
            aria-hidden="true"
          />
        ) : (
          <FileText
            className="h-4 w-4 shrink-0 text-muted-foreground"
            aria-hidden="true"
          />
        )}
        <span className="truncate font-mono">{entry.path}</span>
      </span>
      {!entry.isDirectory && onDelete && !PROTECTED_PATHS.has(entry.path) && (
        <button
          className="opacity-0 group-hover:opacity-100 text-muted-foreground hover:text-destructive"
          onClick={(e) => {
            e.stopPropagation();
            onDelete(entry.path);
          }}
          aria-label={`Delete ${entry.path}`}
        >
          <Trash2 className="h-3.5 w-3.5" />
        </button>
      )}
    </li>
  );
}
