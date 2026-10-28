'use client';

import { useState } from 'react';
import { useAgents, useReloadAgents } from '@/lib/api/queries';
import { useUrlQueryState } from '@/lib/hooks/use-url-query-state';
import { useCan } from '@/lib/auth/use-can';
import { Input } from '@/components/ui/input';
import { Button } from '@/components/ui/button';
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs';
import { Search, RefreshCw, X, Plus } from 'lucide-react';
import { AgentCard } from '@/components/agents/agent-card';
import { RunAgentDialog } from '@/components/agents/run-agent-dialog';
import { NewAgentDialog } from '@/components/agents/new-agent-dialog';
import type { AgentInfo, AgentReloadResult } from '@/types/homer';

const RELOAD_CHANGE_ROWS = [
  { key: 'added', label: 'Added:', className: 'text-green-600 font-medium' },
  { key: 'updated', label: 'Updated:', className: 'text-blue-500 font-medium' },
  { key: 'removed', label: 'Removed:', className: 'text-muted-foreground font-medium' },
] as const;

// Radix tab values. Group tabs are prefixed so a group literally named "all"
// cannot collide with the All tab; `?group=` keeps carrying the bare name.
const ALL_TAB = 'all';
const GROUP_TAB_PREFIX = 'group:';

function groupTabValue(group: string) {
  return `${GROUP_TAB_PREFIX}${group}`;
}

/** The group a tab value selects, or null for the All tab. */
function tabGroup(value: string): string | null {
  return value.startsWith(GROUP_TAB_PREFIX) ? value.slice(GROUP_TAB_PREFIX.length) : null;
}

/** The distinct group names in [agents], sorted by name. Ungrouped agents contribute none. */
function agentGroups(agents: AgentInfo[]): string[] {
  const names = new Set<string>();
  for (const agent of agents) {
    if (agent.group) names.add(agent.group);
  }
  return [...names].sort((a, b) => a.localeCompare(b));
}

/** Whether [agent] belongs to the selected tab and matches the name search. */
function matchesTab(agent: AgentInfo, group: string | null, searchTerm: string): boolean {
  return (group === null || agent.group === group) && agent.name.toLowerCase().includes(searchTerm.toLowerCase());
}

// The page's URL state: the name search and the selected group tab, plus the
// distinct groups to render a tab for and the agents that tab and search leave.
function useAgentFilters(agents: AgentInfo[] | undefined) {
  const { searchParams, setParams } = useUrlQueryState();
  const searchTerm = searchParams.get('q') ?? '';
  const groups = agentGroups(agents ?? []);
  const requested = searchParams.get('group');
  // A `?group=` naming a group that no longer exists falls back to "All".
  const activeGroup = requested && groups.includes(requested) ? requested : null;
  return {
    searchTerm,
    setSearchTerm: (q: string) => setParams({ q }),
    groups,
    activeGroup,
    selectGroup: (group: string | null) => setParams({ group }),
    filteredAgents: agents?.filter((agent) => matchesTab(agent, activeGroup, searchTerm)),
  };
}

// The "All" tab plus one per group; the selected tab lives in the URL's `group`.
function GroupTabs({
  groups,
  activeGroup,
  onSelect,
}: {
  groups: string[];
  activeGroup: string | null;
  onSelect: (group: string | null) => void;
}) {
  return (
    <div className="space-y-2">
      <Tabs
        value={activeGroup === null ? ALL_TAB : groupTabValue(activeGroup)}
        onValueChange={(value) => onSelect(tabGroup(value))}
      >
        <TabsList className="h-auto flex-wrap justify-start">
          <TabsTrigger value={ALL_TAB}>All</TabsTrigger>
          {groups.map((group) => (
            <TabsTrigger key={group} value={groupTabValue(group)}>
              {group}
            </TabsTrigger>
          ))}
        </TabsList>
      </Tabs>
      <p className="text-xs text-muted-foreground">
        Agents without a group appear under &quot;All&quot; only. The name search applies within the selected tab.
      </p>
    </div>
  );
}

// One line per non-empty change list of a reload, or nothing when nothing changed.
function ReloadChanges({ result }: { result: AgentReloadResult }) {
  const rows = RELOAD_CHANGE_ROWS.filter((row) => result[row.key].length > 0);
  if (rows.length === 0) return null;
  return (
    <div className="space-y-1 text-muted-foreground">
      {rows.map((row) => (
        <p key={row.key}><span className={row.className}>{row.label}</span> {result[row.key].join(', ')}</p>
      ))}
    </div>
  );
}

function ReloadResultBanner({
  result,
  onDismiss,
}: {
  result: AgentReloadResult;
  onDismiss: () => void;
}) {
  return (
    <div className={`rounded-lg border p-4 text-sm space-y-2 ${result.errors.length > 0 ? 'border-amber-500 bg-amber-500/10' : 'border-green-600 bg-green-600/10'}`}>
      <div className="flex items-start justify-between gap-2">
        <p className="font-medium">
          Reloaded — {result.added.length} added · {result.updated.length} updated · {result.removed.length} removed
        </p>
        <button onClick={onDismiss} className="text-muted-foreground hover:opacity-70 shrink-0">
          <X className="h-4 w-4" />
        </button>
      </div>
      <ReloadChanges result={result} />
      {result.errors.length > 0 && (
        <div className="space-y-1">
          {result.errors.map((err, i) => (
            <p key={i} className="text-destructive">{err}</p>
          ))}
        </div>
      )}
    </div>
  );
}

function ReloadButton({ isReloading, onClick }: { isReloading: boolean; onClick: () => void }) {
  return (
    <Button
      variant="outline"
      size="sm"
      onClick={onClick}
      disabled={isReloading}
    >
      <RefreshCw className={`h-4 w-4 mr-2 ${isReloading ? 'animate-spin' : ''}`} aria-hidden="true" />
      Reload
    </Button>
  );
}

function AgentsLoadState({ isLoading, error }: { isLoading: boolean; error: Error | null }) {
  return (
    <>
      {isLoading && (
        <div className="flex items-center justify-center py-8">
          <div className="text-muted-foreground">Loading agents...</div>
        </div>
      )}

      {error && (
        <div className="rounded-lg border border-destructive bg-destructive/10 p-4">
          <p className="text-sm text-destructive">
            Failed to load agents: {error.message}
          </p>
        </div>
      )}
    </>
  );
}

// The filtered agent cards, or the empty state for the current search.
function AgentGrid({
  agents,
  searchTerm,
  onRunClick,
}: {
  agents: AgentInfo[];
  searchTerm: string;
  onRunClick: (agent: AgentInfo) => void;
}) {
  if (agents.length === 0) {
    return (
      <div className="flex items-center justify-center py-8">
        <p className="text-muted-foreground">
          {searchTerm ? 'No agents match your search' : 'No agents available'}
        </p>
      </div>
    );
  }
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
      {agents.map((agent) => (
        <AgentCard key={agent.name} agent={agent} onRunClick={onRunClick} />
      ))}
    </div>
  );
}

function ReloadErrorBanner({ message, onDismiss }: { message: string; onDismiss: () => void }) {
  return (
    <div className="rounded-lg border border-destructive bg-destructive/10 p-4 flex items-start justify-between gap-2">
      <p className="text-sm text-destructive">Reload failed: {message}</p>
      <button onClick={onDismiss} className="text-destructive hover:opacity-70 shrink-0">
        <X className="h-4 w-4" />
      </button>
    </div>
  );
}

function AgentSearch({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  return (
    <div className="relative">
      <Search className="absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-muted-foreground" />
      <Input
        placeholder="Search agents..."
        value={value}
        onChange={(e) => onChange(e.target.value)}
        className="pl-9"
      />
    </div>
  );
}

// The reload mutation plus its last outcome: a result banner or an error banner.
function useAgentReload() {
  const [reloadResult, setReloadResult] = useState<AgentReloadResult | null>(null);
  const [reloadError, setReloadError] = useState<string | null>(null);
  const { mutate: reloadAgents, isPending: isReloading } = useReloadAgents();

  function handleReload() {
    setReloadResult(null);
    setReloadError(null);
    reloadAgents(undefined, {
      onSuccess: (result) => setReloadResult(result),
      onError: (err) => setReloadError(err.message),
    });
  }

  return { reloadResult, setReloadResult, reloadError, setReloadError, isReloading, handleReload };
}

export default function AgentsPage() {
  const [selectedAgent, setSelectedAgent] = useState<AgentInfo | null>(null);
  const [runDialogOpen, setRunDialogOpen] = useState(false);
  const [newAgentDialogOpen, setNewAgentDialogOpen] = useState(false);
  const { reloadResult, setReloadResult, reloadError, setReloadError, isReloading, handleReload } =
    useAgentReload();
  const { data, isLoading, error } = useAgents();
  const { isAdmin } = useCan();
  const { searchTerm, setSearchTerm, groups, activeGroup, selectGroup, filteredAgents } = useAgentFilters(
    data?.agents,
  );

  return (
    <div className="p-6 space-y-6">
      <div className="flex flex-col gap-3 md:flex-row md:items-center md:justify-between">
        <h2 className="text-3xl font-bold tracking-tight">Agents</h2>
        <div className="flex items-center gap-2">
          {isAdmin && <ReloadButton isReloading={isReloading} onClick={handleReload} />}
          <Button size="sm" onClick={() => setNewAgentDialogOpen(true)}>
            <Plus className="h-4 w-4 mr-2" aria-hidden="true" />
            New agent
          </Button>
        </div>
      </div>

      <AgentSearch value={searchTerm} onChange={setSearchTerm} />

      <GroupTabs groups={groups} activeGroup={activeGroup} onSelect={selectGroup} />

      {reloadError && <ReloadErrorBanner message={reloadError} onDismiss={() => setReloadError(null)} />}

      {reloadResult && (
        <ReloadResultBanner result={reloadResult} onDismiss={() => setReloadResult(null)} />
      )}

      <AgentsLoadState isLoading={isLoading} error={error} />

      {filteredAgents && (
        <AgentGrid
          agents={filteredAgents}
          searchTerm={searchTerm}
          onRunClick={(agent) => {
            setSelectedAgent(agent);
            setRunDialogOpen(true);
          }}
        />
      )}

      {selectedAgent && (
        <RunAgentDialog
          open={runDialogOpen}
          onOpenChange={setRunDialogOpen}
          agent={selectedAgent}
        />
      )}

      <NewAgentDialog
        open={newAgentDialogOpen}
        onOpenChange={setNewAgentDialogOpen}
      />
    </div>
  );
}
