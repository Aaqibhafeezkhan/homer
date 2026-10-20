import { useQuery, useMutation, useQueryClient, keepPreviousData, type QueryClient } from '@tanstack/react-query';
import { homerApi, HomerApiError, type ExecuteAgentOptions } from './client';
import { appConfig } from '@/lib/config/app-config';
import type {
  ProcessInfo,
  ProcessFilters,
  ProcessListResponse,
  ProcessStatus,
  DebugAgentRequest,
  QuestionStatus,
  ContinuationStatus,
  LangGraphRunStatus,
} from '@/types/homer';
import { triggerDownload } from '@/lib/utils/download';

// Query Keys
export const queryKeys = {
  // Prefix of every process-list key (all filters).
  allProcesses: () => ['processes'] as const,
  processes: (filters?: ProcessFilters) => ['processes', filters] as const,
  // Deliberately outside the `['processes']` prefix — see useFlowSummary.
  allFlowSummaries: () => ['flow-summary'] as const,
  flowSummary: (rootProcessId: number) => ['flow-summary', rootProcessId] as const,
  // Prefix of every process-detail key.
  allProcessDetails: () => ['process'] as const,
  process: (id: number) => ['process', id] as const,
  agents: () => ['agents'] as const,
  artifactContent: (processId: number, filename: string) =>
    ['artifact-content', processId, filename] as const,
  // Prefix of every artifactContent key for one process — used to invalidate
  // all of a process's artifact snapshots at once (e.g. when it finishes).
  artifactContentByProcess: (processId: number) =>
    ['artifact-content', processId] as const,
  artifacts: (processId: number) => ['artifacts', processId] as const,
  heartbeat: () => ['heartbeat'] as const,
  health: () => ['health'] as const,
  costs: () => ['costs'] as const,
  costsMonthly: (month?: string) => ['costs', 'monthly', month ?? 'current'] as const,
  costsTotals: () => ['costs', 'totals'] as const,
  costsByTag: (key?: string) => ['costs', 'by-tag', key ?? null] as const,
  runners: () => ['runners'] as const,
  runnerQueue: () => ['runners', 'queue'] as const,
  agentFiles: (agentName: string) => ['agent-files', agentName] as const,
  agentFile: (agentName: string, path: string) =>
    ['agent-file', agentName, path] as const,
  agentSchema: () => ['agent-schema'] as const,
  // Prefix of every question key (lists and the open count).
  allQuestions: () => ['questions'] as const,
  questions: (filters?: QuestionFilters) => ['questions', filters] as const,
  openQuestionsCount: () => ['questions', 'open-count'] as const,
  // Prefix of every continuation key (lists and the pending count).
  allContinuations: () => ['continuations'] as const,
  continuations: (filters?: ContinuationFilters) => ['continuations', filters] as const,
  continuationsPendingCount: () => ['continuations', 'pending-count'] as const,
  agentLangGraph: (agentName: string) => ['agent-langgraph', agentName] as const,
  processLangGraph: (processId: number, graph: boolean) =>
    ['process-langgraph', processId, graph] as const,
};

export interface QuestionFilters {
  status?: QuestionStatus;
  processId?: number;
}

export interface ContinuationFilters {
  status?: ContinuationStatus;
  agentName?: string;
}

// A process that will not change status again — its view needs no further poll.
// Undefined (no data yet) is not terminal, so polling starts immediately.
// Reads cached state: the KILLED status useKillProcess writes optimistically
// also stops the timer, until its onSettled invalidation refetches the server
// state.
function isTerminalStatus(status?: ProcessStatus): boolean {
  return status === 'FINISHED' || status === 'FAILED' || status === 'KILLED' || status === 'UNKNOWN';
}

// Stops once the page holds rows and every one is terminal — a page left
// open on finished runs has nothing left to poll. An empty page is not
// settled: it must keep polling so a run appearing while SSE was down
// makes it into the view.
function processListRefetchInterval(query: { state: { data?: ProcessListResponse } }): number | false {
  const rows = query.state.data?.processes;
  return rows && rows.length > 0 && rows.every((p) => isTerminalStatus(p.status)) ? false : 30_000;
}

// 30 s safety-net poll under SSE invalidation.
export function useProcesses(filters?: ProcessFilters, enabled = true) {
  return useQuery({
    queryKey: queryKeys.processes(filters),
    queryFn: () => homerApi.getProcesses(filters),
    enabled,
    refetchInterval: processListRefetchInterval,
  });
}

/**
 * A list row's flow summary: the runs under one root. Keyed outside `['processes']` —
 * see console/CLAUDE.md, "A per-row query stays outside the blanket SSE invalidation".
 * The 30 s poll never stops: a continuation can add a child to a flow whose runs have
 * all finished, and no event the stream matches announces it.
 */
export function useFlowSummary(rootProcessId: number, limit: number) {
  return useQuery({
    queryKey: queryKeys.flowSummary(rootProcessId),
    queryFn: () => homerApi.getProcesses({ rootProcessId, limit }),
    refetchInterval: 30_000,
  });
}

// Same 30 s safety net; stops once the status is terminal.
export function useProcess(id: number, enabled = true) {
  const { data } = useQuery({
    queryKey: queryKeys.process(id),
    queryFn: () => homerApi.getProcess(id),
    enabled,
    refetchInterval: (query) => (isTerminalStatus(query.state.data?.status) ? false : 30_000),
  });

  return { data };
}

// Agents Query
export function useAgents() {
  return useQuery({
    queryKey: queryKeys.agents(),
    queryFn: () => homerApi.getAgents(),
    select: (data) => ({
      ...data,
      agents: [...data.agents].sort((a, b) => a.name.localeCompare(b.name)),
    }),
    staleTime: appConfig.polling.agentsCacheTime,
    gcTime: appConfig.polling.agentsCacheTime,
  });
}

// Single Agent Query (derived from cached agents list)
export function useAgent(name: string) {
  const { data, ...rest } = useAgents();
  return {
    ...rest,
    data: data?.agents.find((a) => a.name === name),
  };
}

// Agent LangGraph workflow graphs
export function useAgentLangGraph(name: string) {
  return useQuery({
    queryKey: queryKeys.agentLangGraph(name),
    queryFn: () => homerApi.getAgentLangGraph(name),
  });
}

// Reload Agents Mutation
export function useReloadAgents() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => homerApi.reloadAgents(),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.agents() });
    },
  });
}

// Execute Agent Mutation
export function useExecuteAgent() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: ({
      agentName,
      opts,
    }: {
      agentName: string;
      opts: ExecuteAgentOptions;
    }) => homerApi.executeAgent(agentName, opts),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.allProcesses() });
    },
  });
}

// Kill Process Mutation
export function useKillProcess() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (id: number) => homerApi.killProcess(id),
    onMutate: async (id) => {
      // Cancel outgoing refetches
      await queryClient.cancelQueries({ queryKey: queryKeys.process(id) });

      // Snapshot previous value
      const previousProcess = queryClient.getQueryData(queryKeys.process(id));

      // Optimistically update
      queryClient.setQueryData<ProcessInfo>(queryKeys.process(id), (old) => {
        if (!old) return old;
        return {
          ...old,
          status: 'KILLED' as ProcessStatus,
        };
      });

      return { previousProcess };
    },
    onError: (err, id, context) => {
      // Rollback on error
      if (context?.previousProcess) {
        queryClient.setQueryData(queryKeys.process(id), context.previousProcess);
      }
    },
    onSettled: (data, error, id) => {
      // Refetch process and list
      queryClient.invalidateQueries({ queryKey: queryKeys.process(id) });
      queryClient.invalidateQueries({ queryKey: queryKeys.allProcesses() });
    },
  });
}

// Retry Process Mutation
export function useRetryProcess() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: (id: number) => homerApi.retryProcess(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.allProcesses() });
    },
  });
}

// LangGraph per-run status. Resolves to null (not an error) when the process
// is not a LangGraph run (backend 404), so callers can gate UI on data presence.
export function useProcessLangGraph(
  processId: number,
  opts: { graph: boolean; enabled: boolean; refetchInterval?: number | false },
) {
  return useQuery<LangGraphRunStatus | null>({
    queryKey: queryKeys.processLangGraph(processId, opts.graph),
    queryFn: async () => {
      try {
        return await homerApi.getProcessLangGraph(processId, { graph: opts.graph });
      } catch (e) {
        if (e instanceof HomerApiError && e.status === 404) return null;
        throw e;
      }
    },
    enabled: opts.enabled,
    refetchInterval: opts.refetchInterval ?? false,
    retry: false,
  });
}

export function useResumeProcess() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => homerApi.resumeProcess(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.allProcesses() });
    },
  });
}

// Artifact Download Mutation
export function useDownloadArtifact() {
  return useMutation({
    mutationFn: async ({
      processId,
      artifactPath
    }: {
      processId: number;
      artifactPath: string
    }) => {
      const result = await homerApi.downloadArtifact(processId, artifactPath);

      // Trigger download immediately
      triggerDownload(result.blob, result.filename);

      return result;
    },
  });
}

// Heartbeat Query
export function useHeartbeat() {
  return useQuery({
    queryKey: queryKeys.heartbeat(),
    queryFn: () => homerApi.getHeartbeat(),
    staleTime: Infinity,
    retry: false,
  });
}

// Health Query (runner summary + last startup sweep)
export function useHealth() {
  return useQuery({
    queryKey: queryKeys.health(),
    queryFn: () => homerApi.getHealth(),
    staleTime: 60_000,
    retry: false,
  });
}

// Costs Query — rolling-window Claude spend snapshot
// Auto-refetch every 5 s so the page reflects new spend without
// requiring a hard reload. Backend returns a cheap indexed SQL aggregate.
export function useCosts() {
  return useQuery({
    queryKey: queryKeys.costs(),
    queryFn: () => homerApi.getCosts(),
    refetchInterval: 5_000,
    staleTime: 4_000,
  });
}

// Runner status and claim queue — admin-only operational snapshots, same
// 5 s cadence as costs. Disabled for non-admins so a typed URL does not poll 403s.
export function useRunners(enabled = true) {
  return useQuery({
    queryKey: queryKeys.runners(),
    queryFn: () => homerApi.getRunners(),
    refetchInterval: 5_000,
    staleTime: 4_000,
    enabled,
  });
}

export function useRunnerQueue(enabled = true) {
  return useQuery({
    queryKey: queryKeys.runnerQueue(),
    queryFn: () => homerApi.getRunnerQueue(),
    refetchInterval: 5_000,
    staleTime: 4_000,
    enabled,
  });
}

// Monthly per-agent costs from the persistent ledger. Durable data —
// no fast auto-refetch needed (unlike the rolling-window snapshot).
export function useMonthlyCosts(month?: string) {
  return useQuery({
    queryKey: queryKeys.costsMonthly(month),
    queryFn: () => homerApi.getMonthlyCosts(month),
    staleTime: 30_000,
    placeholderData: keepPreviousData,
  });
}

// All-time per-agent costs from the persistent ledger.
export function useTotalCosts() {
  return useQuery({
    queryKey: queryKeys.costsTotals(),
    queryFn: () => homerApi.getTotalCosts(),
    staleTime: 30_000,
  });
}

// Cost breakdown by one configured tag key's values. Disabled until a key is
// picked (the console offers a selector fed from useCosts().costTrackedTagKeys).
export function useCostsByTag(key?: string) {
  return useQuery({
    queryKey: queryKeys.costsByTag(key),
    queryFn: () => homerApi.getCostsByTag(key as string),
    enabled: !!key,
    staleTime: 30_000,
  });
}

// Agent Files List Query
export function useAgentFiles(agentName: string, enabled = true) {
  return useQuery({
    queryKey: queryKeys.agentFiles(agentName),
    queryFn: () => homerApi.listAgentFiles(agentName),
    enabled: enabled && !!agentName,
    // Agent file listings change only on save/delete (which invalidate this
    // key), so cache them like the agents list instead of refetching on every
    // mount / route switch.
    staleTime: appConfig.polling.agentsCacheTime,
  });
}

// Agent File Content Query
export function useAgentFile(
  agentName: string,
  path: string | null,
  enabled = true,
) {
  return useQuery({
    queryKey: queryKeys.agentFile(agentName, path || ''),
    queryFn: () => homerApi.readAgentFile(agentName, path!),
    enabled: enabled && !!agentName && !!path,
    staleTime: 0,
    gcTime: 5 * 60 * 1000,
  });
}

// Save Agent File Mutation
export function useSaveAgentFile(agentName: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ path, content }: { path: string; content: string }) =>
      homerApi.writeAgentFile(agentName, path, content),
    onSuccess: (_data, vars) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.agents() });
      queryClient.invalidateQueries({
        queryKey: queryKeys.agentFiles(agentName),
      });
      queryClient.invalidateQueries({
        queryKey: queryKeys.agentFile(agentName, vars.path),
      });
    },
  });
}

// Delete Agent File Mutation
export function useDeleteAgentFile(agentName: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (path: string) => homerApi.deleteAgentFile(agentName, path),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.agents() });
      queryClient.invalidateQueries({
        queryKey: queryKeys.agentFiles(agentName),
      });
    },
  });
}

// Create Agent Mutation
export function useCreateAgent() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => homerApi.createAgent(name),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.agents() });
    },
  });
}

// Fire Agent As Its Cron Would Mutation (the /schedules Run button)
export function useFireAgentCron() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (agentName: string) => homerApi.fireAgentCron(agentName),
    onSuccess: () => {
      // The fire records lastRunProcessId in the scheduler; a refetch of the
      // agents list surfaces it in the row's "Last run" cell.
      queryClient.invalidateQueries({ queryKey: queryKeys.agents() });
      queryClient.invalidateQueries({ queryKey: queryKeys.allProcesses() });
    },
  });
}

// Debug Agent Command Mutation
export function useDebugCommand(agentName: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (req: DebugAgentRequest) =>
      homerApi.debugAgentCommand(agentName, req),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.allProcesses() });
    },
  });
}

// Agent Schema Query (for Monaco JSON schema validation)
export function useAgentSchema() {
  return useQuery({
    queryKey: queryKeys.agentSchema(),
    queryFn: () => homerApi.getAgentSchema(),
    staleTime: Infinity,
    gcTime: Infinity,
  });
}

// Questions Query (operator view). SSE invalidation via question.* events is
// the fast path; the 30 s refetch is the safety-net fallback — a dropped
// event or dead stream must not leave a blocking question invisible.
export function useQuestions(filters?: QuestionFilters, enabled = true) {
  return useQuery({
    queryKey: queryKeys.questions(filters),
    queryFn: () => homerApi.getQuestions(filters),
    enabled,
    refetchInterval: 30_000,
  });
}

// Global open-question count for the nav badge. SSE invalidation is the
// fast path; the 30 s refetch is the safety-net fallback.
export function useOpenQuestionsCount() {
  return useQuery({
    queryKey: queryKeys.openQuestionsCount(),
    queryFn: () => homerApi.getQuestions({ status: 'OPEN' }),
    // `total`, not the page's length: the list is paged (100 by default).
    select: (data) => data.total,
    refetchInterval: 30_000,
  });
}

/**
 * Refetches what settling a question changes: the question lists, and the
 * process list and details, since answering or cancelling one moves the
 * process waiting on it.
 */
function invalidateQuestionViews(queryClient: QueryClient): void {
  queryClient.invalidateQueries({ queryKey: queryKeys.allQuestions() });
  queryClient.invalidateQueries({ queryKey: queryKeys.allProcesses() });
  queryClient.invalidateQueries({ queryKey: queryKeys.allProcessDetails() });
}

// Answer Question Mutation
export function useAnswerQuestion() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, answer }: { id: string; answer: string }) =>
      homerApi.answerQuestion(id, answer),
    onSettled: () => {
      // Settled, not success: a 409 (already answered / expired) means our
      // view was stale, so refetching is exactly what we want.
      invalidateQuestionViews(queryClient);
    },
  });
}

// Cancel Dispatch Mutation — cancels a pending ask-and-dispatch question.
export function useCancelDispatch() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => homerApi.cancelDispatch(id),
    onSettled: () => {
      // Settled, not success: a 404/409 (already answered / fired / cancelled)
      // means our view was stale, so refetching is exactly what we want.
      invalidateQuestionViews(queryClient);
    },
  });
}

// Continuations Query (operator view). 30 s refetch is the safety net — there
// is no continuation SSE event, so polling keeps the list reasonably fresh.
export function useContinuations(filters?: ContinuationFilters, enabled = true) {
  return useQuery({
    queryKey: queryKeys.continuations(filters),
    queryFn: () => homerApi.getContinuations(filters),
    enabled,
    refetchInterval: 30_000,
  });
}

// Pending-continuation count for the nav badge. `total` counts every matching
// row, so the badge stays correct past the 100-row page cap. There is no
// continuation SSE event, so the 30 s refetch is the only refresh path.
export function usePendingContinuationsCount() {
  return useQuery({
    queryKey: queryKeys.continuationsPendingCount(),
    queryFn: () => homerApi.getContinuations({ status: 'PENDING' }),
    select: (data) => data.total,
    refetchInterval: 30_000,
  });
}

// Cancel Continuation Mutation — cancels a pending completion continuation.
export function useCancelContinuation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => homerApi.cancelContinuation(id),
    onSettled: () => {
      // Settled, not success: a 404/409 (already fired / cancelled / missing)
      // means our view was stale, so refetching is exactly what we want.
      queryClient.invalidateQueries({ queryKey: queryKeys.allContinuations() });
      queryClient.invalidateQueries({ queryKey: queryKeys.allProcesses() });
      queryClient.invalidateQueries({ queryKey: queryKeys.allProcessDetails() });
    },
  });
}

// Artifact Content Query
export function useArtifactContent(
  processId: number,
  artifactPath: string | null,
  enabled: boolean = true
) {
  return useQuery({
    queryKey: queryKeys.artifactContent(processId, artifactPath || ''),
    queryFn: () => homerApi.fetchArtifactContent(processId, artifactPath!),
    enabled: enabled && !!artifactPath,
    staleTime: 30000, // Fresh for 30 seconds
    gcTime: 5 * 60 * 1000, // Cache for 5 minutes
    retry: 1,
  });
}
