// Process Status
export type ProcessStatus = 'CREATED' | 'WORKING' | 'FINISHED' | 'FAILED' | 'KILLED' | 'UNKNOWN';

// Process History Entry
export interface ProcessHistoryEntry {
  timestamp: number;
  status: ProcessStatus;
}

export type CostSource = 'cli' | 'session' | 'none';
export type ProcessCostSource = CostSource | 'mixed';

// How one LLM harness command's (Claude, Codex or opencode) spend was priced. Null for non-LLM commands.
export interface LlmAccounting {
  sessionId: string | null;
  costUsd: number;
  source: CostSource;
  fallbackReason?: string | null;
  // Token usage summed over every turn end of the winning attempt; null when no turn end was seen.
  usage?: LlmUsage | null;
}

// Process Execution
export interface ProcessExecution {
  label: string;
  start: number;
  end: number;
  resultCode: number;
  stdOut: string | null;
  stdErr: string | null;
  errMsg: string | null;
  skipped?: boolean;
  llmAccounting?: LlmAccounting | null;
  // Command tag (`cmd`, `script`, `claude`, `codex`, `opencode`, `agent`, `langgraph`, `runnerCmd`); "" for older rows.
  kind?: string;
}

// Pod Phase (Kubernetes runner)
export type PodPhase =
  | 'NONE'
  | 'CREATING'
  | 'PENDING'
  | 'RUNNING'
  | 'READY'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'DELETED'
  | 'KEPT_FOR_DEBUG';

// Pod Startup Failure
export interface PodStartupFailure {
  reason: string;
  message: string;
  events: string[];
}

// Runner State (per-process)
export interface RunnerState {
  // Raw backend alias; null for a non-admin viewer, who gets the generic `type`
  // instead (CWE-200, fail closed). See ADR-0077.
  alias?: string | null;
  // Generic backend kind (`local` / `ssh` / `kubernetes` / `external` / `unknown`),
  // always present.
  type: string;
  podName?: string | null;
  podPhase: PodPhase;
  createdAt?: number | null;
  readyAt?: number | null;
  endedAt?: number | null;
  startupFailure?: PodStartupFailure | null;
}

// Process Info
export interface ProcessInfo {
  id: number;
  status: ProcessStatus;
  agentName: string;
  // The agent's group, read from the current agent definition at response time;
  // null when the agent is ungrouped or has since been removed.
  group?: string | null;
  owner?: string | null;
  history: ProcessHistoryEntry[];
  executions: ProcessExecution[];
  currentCommand: string | null;
  currentCommandStart: number | null;
  // Tag of the command running now, from in-memory tracking; null when nothing runs or after a restart.
  currentCommandKind?: string | null;
  // Index of the command running now, from in-memory tracking; null when nothing runs or after a restart.
  currentCommandIndex?: number | null;
  purgeAt: number | null;  // Unix epoch seconds; null if active or purge not yet scheduled
  retainedTruncated?: boolean | null;
  inputParams?: Record<string, string>;
  runner?: RunnerState | null;
  openQuestions?: number;
  // Billed Claude spend for the run, from the cost ledger — survives artifact purge,
  // unlike the figure parsed out of cmd_N.out.
  costUsd?: number;
  // Which pricing rung produced costUsd. Absent on older servers = 'cli'.
  costSource?: ProcessCostSource;
  // Deep link to this run's Langfuse trace; null unless langfuse.serverUrl and
  // langfuse.projectId are configured on the server.
  langfuseTraceUrl?: string | null;
  // Id of the run that caused this one (RunAgent parent, dispatch/continuation
  // origin, or an external caller's header; a retry or resume keeps the source
  // run's parent); null at the root.
  parentProcessId?: number | null;
  // Id of the first run of this run's flow; null at the root itself. Filter a
  // whole flow below its root with `?rootProcessId=`. See ADR-0058.
  rootProcessId?: number | null;
  // Subject tags this run carries: `key:value` strings resolved from inputs at
  // start and/or declared at run time. Filter the list with `?tag=`.
  tags?: string[];
  // Why the run ended FAILED before any command started (claim timeout, lock
  // skip, parallel-run cap, full queue); absent for every other failure. The
  // run that blocked this one is named in the text only when the reader may
  // see it. See ADR-0058.
  failureReason?: string | null;
}

// Question Status (human-in-the-loop questions)
export type QuestionStatus = 'OPEN' | 'ANSWERED' | 'EXPIRED';

// Dispatch summary — present when a question is bound to an agent dispatch
export interface DispatchSummary {
  id: number;
  agentName: string;
  status: 'PENDING' | 'DISPATCHING' | 'DISPATCHED' | 'FAILED' | 'CANCELLED';
  dispatchedProcessId?: number | null;
  error?: string | null;
}

// Question asked by a running command, answered by the operator
export interface Question {
  id: string;
  processId: number;
  agentName: string;
  text: string;        // Markdown
  options: string[];   // predefined answers (rendered as buttons); may be empty
  status: QuestionStatus;
  answer: string | null;
  createdAt: number;   // Unix epoch seconds
  answeredAt: number | null;
  dispatch?: DispatchSummary | null;
}

// A page, oldest first: `total` counts every visible question matching the filters.
export interface QuestionsListResponse {
  questions: Question[];
  total: number;
  offset: number;
  limit: number;
}

// Operator message sent to a running run (its inbox)
export type RunMessageStatus = 'QUEUED' | 'DELIVERED' | 'UNDELIVERED';

export interface RunMessage {
  id: string;
  processId: number;
  text: string;
  status: RunMessageStatus;
  sender?: string | null;
  createdAt: number;   // Unix epoch seconds
  deliveredAt?: number | null;
}

// Oldest first.
export interface RunMessagesListResponse {
  messages: RunMessage[];
}

export interface SendRunMessageRequest {
  text: string;
}

// Continuation Status (completion continuations — a parked workflow
// waiting on a watched sub-process to finish)
export type ContinuationStatus =
  | 'PENDING'
  | 'DISPATCHING'
  | 'DISPATCHED'
  | 'FAILED'
  | 'CANCELLED'
  | 'EXPIRED';

// Completion continuation — mirrors backend ContinuationResponse
export interface Continuation {
  id: number;
  watchedProcessId: number;
  status: ContinuationStatus;
  agentName: string;             // target agent that fires when the watched process finishes
  resumeParam: string;
  params: Record<string, string>;
  originProcessId: number;
  originAgentName: string;
  expiresAt?: number | null;     // Unix epoch seconds
  createdAt: number;             // Unix epoch seconds
  firedAt?: number | null;       // Unix epoch seconds
  firedProcessId?: number | null;
  error?: string | null;
}

// A page, newest first: `total` counts every visible row matching the filters.
export interface ContinuationsListResponse {
  continuations: Continuation[];
  total: number;
  offset: number;
  limit: number;
}

// Process List Response
export interface ProcessListResponse {
  processes: ProcessInfo[];
  total: number;
  offset: number;
  limit: number;
}

// Agent Parameter Transformer
export interface AgentParameterTransformer {
  type: 'pass' | 'jsonPath';
  path?: string;
}

// Agent Input source — where the parameter is read from on the HTTP request
export type ParamSource = 'query' | 'body' | 'header';

// Agent Input — canonical name for a declared parameter (was: AgentQueryParam)
export interface AgentInput {
  paramName: string;
  envName: string;
  src: ParamSource;
  type: 'STRING' | 'NUMBER' | 'ANY' | 'string' | 'integer' | 'number' | 'boolean';
  required: boolean;
  transformer: AgentParameterTransformer;
  /** Redacted input: its `default` and `values` are never rendered. */
  secret?: boolean;
  // Typed-generation constraints from backend `Param`; absent on the legacy
  // STRING/NUMBER/ANY types (ADR-0031).
  values?: string[] | null;
  pattern?: string | null;
  min?: number | null;
  max?: number | null;
  minLength?: number | null;
  maxLength?: number | null;
  default?: string | null;
}

/** @deprecated Use `AgentInput`. */
export type AgentQueryParam = AgentInput;

// Agent Cron Info
export interface AgentCronInfo {
  expression: string;
  timezone: string;
  nextRunAt?: number | null;       // Unix epoch seconds
  lastRunAt?: number | null;       // Unix epoch seconds
  lastRunProcessId?: number | null;
}

// Agent Info
export interface AgentInfo {
  name: string;
  description?: string | null;
  // Optional group label; null when unset. The Agents page renders one tab per
  // distinct group, and ungrouped agents appear under "All" only.
  group?: string | null;
  inputs: AgentInput[] | null;
  /** @deprecated Use `inputs`. */
  queryParams: AgentInput[] | null;
  scriptCount: number;
  cron?: AgentCronInfo | null;
  writable?: boolean;
}

// Agent File Entry (directory listing)
export interface AgentFileEntry {
  path: string;
  size: number;
  mtime: number;
  isDirectory: boolean;
}

export interface AgentFileListResponse {
  entries: AgentFileEntry[];
}

export interface AgentFileContentResponse {
  path: string;
  content: string;
}

export interface AgentFileWriteResponse {
  path: string;
  ok: boolean;
}

export interface CreateAgentResponse {
  name: string;
}

export interface SchemaErrorResponse {
  msg: string;
  errors: string[];
}

export interface DebugAgentRequest {
  commandIndex: number;
  env?: Record<string, string>;
  queryParams?: Record<string, string>;
  body?: Record<string, unknown> | null;
  headers?: Record<string, string>;
}

// LangGraph topology (ADR-0073): the console's own minimal rendering
// contract, not LangGraph's Graph.to_json(). Bounds (node/edge counts,
// id/label length) are validated server-side; the console applies its own
// render cap in lib/langgraph/layout.ts.
export interface LangGraphTopologyNode {
  id: string;
}

export interface LangGraphTopologyEdge {
  source: string;
  target: string;
  label?: string | null;
  conditional?: boolean;
}

export interface LangGraphTopology {
  nodes: LangGraphTopologyNode[];
  edges: LangGraphTopologyEdge[];
}

// A topology edge a run has actually taken (ADR-0075).
export interface LangGraphEdgeRef {
  source: string;
  target: string;
}

// LangGraph workflow graph info (one entry per langgraph command)
export interface LangGraphGraphInfo {
  label: string;
  module: string;
  topology?: LangGraphTopology | null;
  error?: string | null;
}

// LangGraph sub-run progress (one entry per interrupt parked at a fan-out node)
// One command of a sub run: state is ok | failed | skipped | running
export interface LangGraphSubCommand {
  label: string;
  state: string;
}

export interface LangGraphSubProgress {
  interruptId: string;
  processId?: number | null;
  agentName?: string | null;
  status?: string | null;
  commands?: LangGraphSubCommand[] | null;
}

// LangGraph per-run status (process-scoped; nodes/topology present only when fetched with graph=true)
export interface LangGraphRunStatus {
  label: string;
  module: string;
  threadId: string;
  segment?: string | null;
  topology?: LangGraphTopology | null;
  nodes?: Record<string, string> | null;
  errors?: Record<string, string> | null;
  subs?: Record<string, LangGraphSubProgress[]> | null;
  // Nodes whose interrupt history hit the runtime's per-node cap, so their
  // subs list may be missing its oldest entries.
  subsTruncated?: string[] | null;
  // Edges the run has taken, already filtered to this run's topology (ADR-0075).
  traversed?: LangGraphEdgeRef[] | null;
  error?: string | null;
}

// Agents List Response
export interface AgentsListResponse {
  agents: AgentInfo[];
}

// Process Status Response (for kill operation)
export interface ProcessStatusResponse {
  status: ProcessStatus;
}

// Heartbeat Response
export interface HeartbeatResponse {
  status: string;
  version?: string;
}

// Runner Summary (health endpoint)
export interface RunnerSummary {
  // Raw backend alias; null for a non-admin viewer, who gets the generic `type`
  // instead (CWE-200, fail closed). See ADR-0077.
  alias?: string | null;
  type: string;
}

// Orphan pod deleted during startup sweep
export interface SweepDeletedPod {
  namespace: string;
  name: string;
  processId?: number | null;
  // Null for a non-admin viewer — same alias classification as `RunnerState.alias`.
  // See ADR-0077.
  runnerAlias?: string | null;
}

// Startup sweep status
export interface SweepStatus {
  ranAt: number;
  scanned: number;
  deleted: SweepDeletedPod[];
  errors: string[];
}

// Health Response
export interface HealthResponse {
  status: string;
  runners: RunnerSummary[];
  sweep?: SweepStatus | null;
  // Admin maintenance mode: cron is skipped and new starts queue while it is on.
  maintenance?: boolean;
}

// Runner status (GET /api/v1/runners) — admin only. Timestamps are epoch seconds.
export interface RunnerInstanceStatus {
  instanceId: string;
  name: string;
  tags: string[];
  concurrency: number;
  activeSessions: number;
  lastSeenAt: number;
  registeredAt: number;
  draining: boolean;
}

export interface RunnerStatus {
  alias: string;
  kind: 'local' | 'ssh' | 'kubernetes' | 'external';
  tags: string[];
  /** null = unbounded */
  concurrency?: number | null;
  inUse: number;
  /** external domains only */
  instances?: RunnerInstanceStatus[] | null;
}

export interface RunnersStatusResponse {
  runners: RunnerStatus[];
}

// Claim queue (GET /api/v1/runners/queue) — exactly one of alias/tags is set.
export interface RunnerFilterView {
  alias?: string | null;
  tags?: string[] | null;
}

export interface RunnerQueueGroup {
  filter: RunnerFilterView;
  pending: number;
  oldestAgeSec: number;
}

export interface RunnerQueueStatusResponse {
  pending: number;
  oldestAgeSec?: number | null;
  byFilter: RunnerQueueGroup[];
}

// Costs Response — snapshot of CostMeter state
export interface CostsResponse {
  globalUsdLast60s: number;
  globalCapUsdPerMin?: number | null;
  perAgentUsdLast24h: Record<string, number>;
  perAgentCapUsdPerDay: Record<string, number>;
  snapshotAtSec: number;
  costTrackedTagKeys: string[];
}

// Monthly per-agent Claude costs from the persistent ledger (UTC months)
export interface MonthlyCostsResponse {
  month: string;
  perAgentUsd: Record<string, number>;
  totalUsd: number;
  availableMonths: string[];
}

// All-time per-agent Claude costs from the persistent ledger
export interface TotalCostsResponse {
  perAgentUsd: Record<string, number>;
  totalUsd: number;
}

// Claude cost broken down by the values of one configured run subject-tag key
export interface CostByTagResponse {
  key: string;
  valuesUsd: Record<string, number>;
  totalUsd: number;
  // True when more runs carried this tag than the backend's row bound summed.
  truncated: boolean;
}

// Process List Filters
export interface ProcessFilters {
  types?: ProcessStatus[];
  agentName?: string;
  // Direct children of one run.
  parentProcessId?: number;
  // Every run below the flow root (the root's own column is NULL, so it is
  // not matched) (ADR-0058).
  rootProcessId?: number;
  // Roots only: the first run of each flow (`parent_process_id IS NULL`).
  // Composes with every other filter here (AND). See ADR-0058.
  roots?: boolean;
  // Subject-tag filters (`key:value`), ANDed by the backend.
  tags?: string[];
  limit?: number;
  offset?: number;
  order?: 'asc' | 'desc';
}

// Agent Execution Response
export interface AgentExecutionResponse {
  processId: number;
}

// Agent Reload Result
export interface AgentReloadResult {
  added: string[];
  updated: string[];
  removed: string[];
  errors: string[];
}

// Password-auth — mirrors AuthConfig.Password's HTTP responses in app/SERVER.md.
export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  username: string;
}

export type Action = 'run' | 'read' | 'control' | 'edit' | 'debug';
export type Role = 'admin' | null;

export interface Grant {
  agents: string[];
  actions: Action[];
}

export interface MeResponse {
  username: string;
  role: Role;
  grants: Grant[];
}

// ── Canonical LLM events ────────────────────────────────────────────
// Mirror of app/src/main/kotlin/homer/app/models/LlmEvent.kt, streamed
// over SSE as `llm.event` from GET /api/v1/stream/processes/{id}/llm-events.

export interface LlmUsage {
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
  reasoning: number;
}

export type LlmTurnStatus =
  | { type: 'success' }
  | { type: 'failed'; reason: string }
  | { type: 'stopped'; reason: string };

export interface LlmSessionStart { type: 'session_start'; sessionId: string | null; model: string | null; tools: string[] }
export interface LlmAssistantMessage { type: 'assistant_message'; messageId: string | null; model: string | null; text: string; subagent: boolean }
export interface LlmToolCall { type: 'tool_call'; id: string; name: string; input: unknown; messageId: string | null }
export interface LlmToolResult { type: 'tool_result'; id: string; output: unknown; isError: boolean }
export interface LlmTurnEnd {
  type: 'turn_end';
  status: LlmTurnStatus;
  usage: LlmUsage;
  costUsd: number | null;
  model: string | null;
  durationMs: number | null;
  durationApiMs: number | null;
  numTurns: number | null;
  resultText: string | null;
}
export interface LlmTransientError { type: 'transient_error'; message: string }

export type LlmEvent = LlmSessionStart | LlmAssistantMessage | LlmToolCall | LlmToolResult | LlmTurnEnd | LlmTransientError;

/** Wire shape of an `llm.event` SSE frame. */
export interface LlmStreamFrame { processId: number; cmdIndex: number; attemptIndex: number; event: LlmEvent }

/** Command kinds whose stdout the server can translate into LlmEvents. */
const LLM_KINDS = ['claude', 'codex', 'opencode'] as const;
export function isLlmKind(kind: string | null | undefined): boolean {
  return (LLM_KINDS as readonly string[]).includes(kind ?? '');
}
