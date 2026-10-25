import { appConfig } from '@/lib/config/app-config';
import { activeBaseUrl, activeMode, getActiveToken } from '@/lib/instances/store';
import { notifyAuthExpired } from '@/lib/auth/auth-expired-bus';
import type {
  ProcessListResponse,
  ProcessInfo,
  AgentsListResponse,
  ProcessStatusResponse,
  HeartbeatResponse,
  HealthResponse,
  CostsResponse,
  MonthlyCostsResponse,
  TotalCostsResponse,
  CostByTagResponse,
  ProcessFilters,
  AgentExecutionResponse,
  AgentReloadResult,
  AgentFileListResponse,
  AgentFileContentResponse,
  AgentFileWriteResponse,
  CreateAgentResponse,
  DebugAgentRequest,
  LoginRequest,
  LoginResponse,
  MeResponse,
  Question,
  QuestionsListResponse,
  QuestionStatus,
  ContinuationsListResponse,
  ContinuationStatus,
  LangGraphGraphInfo,
  LangGraphRunStatus,
  RunnersStatusResponse,
  RunnerQueueStatusResponse,
} from '@/types/homer';
import { extractFilename } from '@/lib/utils/download';

/**
 * Custom header required by the backend CSRF guard on cookie-auth requests
 * (ADR-0016). Sent on every request — harmless in bearer mode; a cross-site
 * request cannot set it, which is the point.
 */
export const CSRF_HEADER = 'X-Homer-CSRF';

// 401 from the auth probe / login means "anon" / "bad credentials",
// not "session expired" — firing the bus there loops the expiry
// recovery (see use-auth-expired-handler.ts latch comment).
const AUTH_BUS_EXEMPT = ['/api/v1/auth/me', '/api/v1/auth/login'];

export interface ExecuteAgentOptions {
  queryParams?: Record<string, string>;
  body?: Record<string, unknown> | null;
  headers?: Record<string, string>;
}

/**
 * Path (with query string) of `POST /api/v1/agent/{name}`: the agent name is
 * URL-encoded, and a query parameter whose value is blank is left out. Shared by
 * the client and the copy-as-curl command so both target the same URL.
 */
export function agentExecutionPath(
  agentName: string,
  queryParams: Record<string, string> = {},
): string {
  const qs = new URLSearchParams();
  Object.entries(queryParams).forEach(([k, v]) => {
    if (v.trim()) qs.append(k, v);
  });
  const queryString = qs.toString();
  return `/api/v1/agent/${encodeURIComponent(agentName)}${queryString ? `?${queryString}` : ''}`;
}

export interface ArtifactEntry {
  path: string;
  size: number;
  modified: number;
}

export interface ArtifactListing {
  processId: number;
  artifactsDir: string;
  files: ArtifactEntry[];
}

/**
 * Why a request failed without an HTTP status: the client timeout fired, the
 * fetch itself was rejected (down, unreachable, or blocked by CORS — the browser
 * does not say which), or a response arrived that could not be read.
 */
export type TransportFailure = 'timeout' | 'unreachable' | 'invalid-response';

class HomerApiError extends Error {
  public failure?: TransportFailure;

  constructor(
    message: string,
    public status?: number,
    public statusText?: string,
    public body?: unknown,
  ) {
    super(message);
    this.name = 'HomerApiError';
  }
}

/** The JSON body of a failed response, or `undefined` when it has none. */
async function readErrorBody(response: Response): Promise<unknown> {
  try {
    return await response.clone().json();
  } catch {
    return undefined;
  }
}

function notifyIfSessionExpired(status: number, endpoint: string): void {
  if (status === 401 && appConfig.auth.enabled && !AUTH_BUS_EXEMPT.includes(endpoint)) {
    notifyAuthExpired('http-401');
  }
}

class HomerApiClient {
  private timeout: number;

  constructor() {
    this.timeout = appConfig.api.timeout;
  }

  /**
   * Base URL of the active instance, resolved per request (not frozen at
   * construction) so switching the active instance retargets every call.
   */
  private get baseUrl(): string {
    return activeBaseUrl();
  }

  public getAuthToken(): string | null {
    if (typeof window === 'undefined') return null;
    if (activeMode() !== 'bearer') return null;
    return getActiveToken();
  }

  /**
   * `{ Authorization: 'Bearer …' }` in bearer mode with a token, else empty.
   * Spread it last into a request's headers so a caller cannot override it.
   */
  public authorizationHeader(): Record<string, string> {
    const token = this.getAuthToken();
    return appConfig.auth.enabled && activeMode() === 'bearer' && token
      ? { Authorization: `Bearer ${token}` }
      : {};
  }

  /**
   * Returns `credentials: 'include'` in cookie mode so the browser attaches
   * the active instance's session cookie cross-origin (console and API on
   * different origins). Bearer mode doesn't need it — the credential rides in
   * the header.
   */
  private credentials(): RequestCredentials | undefined {
    return activeMode() === 'cookie' ? 'include' : undefined;
  }

  private async request<T>(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<T> {
    return this.send(endpoint, options, { 'Content-Type': 'application/json' }, async (response) => {
      if (!response.ok) {
        const body = await readErrorBody(response);
        notifyIfSessionExpired(response.status, endpoint);
        throw new HomerApiError(
          `API request failed: ${response.statusText}`,
          response.status,
          response.statusText,
          body,
        );
      }

      if (response.status === 204) {
        return undefined as T;
      }

      return await response.json();
    });
  }

  private async requestBlob(
    endpoint: string,
    options: RequestInit = {}
  ): Promise<{ blob: Blob; headers: Headers }> {
    return this.send(endpoint, options, {}, async (response) => {
      if (!response.ok) {
        if (response.status === 401 && appConfig.auth.enabled) {
          notifyAuthExpired('http-401');
        }
        throw new HomerApiError(
          `API request failed: ${response.statusText}`,
          response.status,
          response.statusText
        );
      }

      const blob = await response.blob();
      return { blob, headers: response.headers };
    });
  }

  /**
   * The transport under {@link request} and {@link requestBlob}: fetches
   * `endpoint` on the active instance with the CSRF and auth headers, the
   * instance's credentials mode and the client timeout, then hands the
   * response to `read`. The timeout covers the fetch only, not `read`; any
   * failure (network, abort, or one `read` throws) surfaces as a
   * {@link HomerApiError}. `defaults` go first, so the caller's headers can
   * override them and the auth header overrides everything.
   */
  private async send<T>(
    endpoint: string,
    options: RequestInit,
    defaults: Record<string, string>,
    read: (response: Response) => Promise<T>,
  ): Promise<T> {
    const url = `${this.baseUrl}${endpoint}`;

    const headers: Record<string, string> = {
      ...defaults,
      [CSRF_HEADER]: '1',
      ...(options.headers as Record<string, string>),
      ...this.authorizationHeader(),
    };

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), this.timeout);
    let responded = false;

    try {
      const response = await fetch(url, {
        ...options,
        headers,
        credentials: this.credentials(),
        signal: controller.signal,
      });

      clearTimeout(timeoutId);
      responded = true;

      return await read(response);
    } catch (error) {
      clearTimeout(timeoutId);
      throw toHomerApiError(error, responded);
    }
  }

  async getProcesses(filters?: ProcessFilters): Promise<ProcessListResponse> {
    const params = processFilterParams(filters);

    params.append('order', filters?.order || 'desc');
    params.append('limit', String(filters?.limit || appConfig.pagination.defaultLimit));
    params.append('offset', String(filters?.offset || 0));

    const queryString = params.toString();
    return this.request<ProcessListResponse>(
      `/api/v1/status/all${queryString ? `?${queryString}` : ''}`
    );
  }

  async getProcess(id: number): Promise<ProcessInfo> {
    return this.request<ProcessInfo>(`/api/v1/status/${id}`);
  }

  async getAgents(): Promise<AgentsListResponse> {
    return this.request<AgentsListResponse>('/api/v1/agents');
  }

  async getAgentLangGraph(agentName: string): Promise<LangGraphGraphInfo[]> {
    return this.request<LangGraphGraphInfo[]>(
      `/api/v1/agents/${encodeURIComponent(agentName)}/langgraph`,
    );
  }

  async reloadAgents(): Promise<AgentReloadResult> {
    return this.request<AgentReloadResult>('/api/v1/agents/reload', { method: 'POST' });
  }

  async executeAgent(
    agentName: string,
    opts: ExecuteAgentOptions,
  ): Promise<AgentExecutionResponse> {
    const endpoint = agentExecutionPath(agentName, opts.queryParams);
    const hasBody = !!(opts.body && Object.keys(opts.body).length > 0);
    return this.request<AgentExecutionResponse>(endpoint, {
      method: 'POST',
      headers: {
        ...(opts.headers ?? {}),
        ...(hasBody ? { 'Content-Type': 'application/json' } : {}),
      },
      body: hasBody ? JSON.stringify(opts.body) : undefined,
    });
  }

  async killProcess(id: number): Promise<ProcessStatusResponse> {
    return this.request<ProcessStatusResponse>(`/api/v1/kill/${id}`, {
      method: 'DELETE',
    });
  }

  async retryProcess(id: number): Promise<{ processId: number }> {
    return this.request<{ processId: number }>(
      `/api/v1/processes/${id}/retry`,
      { method: 'POST' },
    );
  }

  /** Fire an agent as its cron would (source `cron`, owner `system:cron`, no inputs). */
  async fireAgentCron(agentName: string): Promise<void> {
    await this.request<void>(
      `/api/v1/agents/${encodeURIComponent(agentName)}/cron-run`,
      { method: 'POST' },
    );
  }

  async getProcessLangGraph(
    id: number,
    opts?: { graph?: boolean },
  ): Promise<LangGraphRunStatus> {
    const qs = opts?.graph ? '?graph=true' : '';
    return this.request<LangGraphRunStatus>(`/api/v1/processes/${id}/langgraph${qs}`);
  }

  async resumeProcess(id: number): Promise<{ processId: number }> {
    return this.request<{ processId: number }>(
      `/api/v1/processes/${id}/resume`,
      { method: 'POST' },
    );
  }

  async getQuestions(filters?: {
    status?: QuestionStatus;
    processId?: number;
  }): Promise<QuestionsListResponse> {
    const params = new URLSearchParams();
    if (filters?.status) params.set('status', filters.status);
    if (filters?.processId !== undefined) params.set('processId', String(filters.processId));
    const qs = params.toString();
    return this.request<QuestionsListResponse>(`/api/v1/questions${qs ? `?${qs}` : ''}`);
  }

  async answerQuestion(id: string, answer: string): Promise<Question> {
    return this.request<Question>(
      `/api/v1/questions/${encodeURIComponent(id)}/answer`,
      {
        method: 'POST',
        body: JSON.stringify({ answer }),
      },
    );
  }

  async cancelDispatch(id: number): Promise<void> {
    await this.request<void>(`/api/v1/dispatches/${id}`, { method: 'DELETE' });
  }

  async getContinuations(filters?: {
    status?: ContinuationStatus;
    agentName?: string;
  }): Promise<ContinuationsListResponse> {
    const params = new URLSearchParams();
    if (filters?.status) params.set('status', filters.status);
    if (filters?.agentName) params.set('agentName', filters.agentName);
    const qs = params.toString();
    return this.request<ContinuationsListResponse>(
      `/api/v1/continuations${qs ? `?${qs}` : ''}`,
    );
  }

  async cancelContinuation(id: number): Promise<void> {
    await this.request<void>(`/api/v1/continuations/${id}`, { method: 'DELETE' });
  }

  async getHeartbeat(): Promise<HeartbeatResponse> {
    return this.request<HeartbeatResponse>('/api/v1/heartbeat');
  }

  async getHealth(): Promise<HealthResponse> {
    return this.request<HealthResponse>('/api/v1/health');
  }

  async getCosts(): Promise<CostsResponse> {
    return this.request<CostsResponse>('/api/v1/costs');
  }

  async getMonthlyCosts(month?: string): Promise<MonthlyCostsResponse> {
    const qs = month ? `?${new URLSearchParams({ month }).toString()}` : '';
    return this.request<MonthlyCostsResponse>(`/api/v1/costs/monthly${qs}`);
  }

  async getTotalCosts(): Promise<TotalCostsResponse> {
    return this.request<TotalCostsResponse>('/api/v1/costs/totals');
  }

  async getCostsByTag(key: string): Promise<CostByTagResponse> {
    return this.request<CostByTagResponse>(`/api/v1/costs/by-tag/${encodeURIComponent(key)}`);
  }

  async getRunners(): Promise<RunnersStatusResponse> {
    return this.request<RunnersStatusResponse>('/api/v1/runners');
  }

  async getRunnerQueue(): Promise<RunnerQueueStatusResponse> {
    return this.request<RunnerQueueStatusResponse>('/api/v1/runners/queue');
  }

  async listArtifacts(processId: number): Promise<ArtifactListing> {
    const params = new URLSearchParams({ processId: String(processId) });
    return this.request<ArtifactListing>(`/api/v1/artifacts/list?${params.toString()}`);
  }

  async downloadArtifact(processId: number, artifactPath: string): Promise<{
    blob: Blob;
    filename: string;
    isComplete: boolean;
    processStatus: string;
  }> {
    const params = new URLSearchParams({
      processId: String(processId),
      path: artifactPath,
    });

    const { blob, headers } = await this.requestBlob(
      `/api/v1/artifacts?${params.toString()}`
    );

    return {
      blob,
      filename: extractFilename(artifactPath),
      isComplete: headers.get('X-File-Complete') === 'true',
      processStatus: headers.get('X-Process-Status') || 'UNKNOWN',
    };
  }

  async listAgentFiles(agentName: string): Promise<AgentFileListResponse> {
    return this.request<AgentFileListResponse>(
      `/api/v1/agents/${encodeURIComponent(agentName)}/files`,
    );
  }

  async readAgentFile(agentName: string, path: string): Promise<AgentFileContentResponse> {
    return this.request<AgentFileContentResponse>(
      agentFilePath(agentName, path),
    );
  }

  async writeAgentFile(
    agentName: string,
    path: string,
    content: string,
  ): Promise<AgentFileWriteResponse> {
    return this.request<AgentFileWriteResponse>(
      agentFilePath(agentName, path),
      {
        method: 'PUT',
        body: JSON.stringify({ content }),
      },
    );
  }

  async deleteAgentFile(agentName: string, path: string): Promise<void> {
    await this.request<void>(
      agentFilePath(agentName, path),
      { method: 'DELETE' },
    );
  }

  async createAgent(name: string): Promise<CreateAgentResponse> {
    return this.request<CreateAgentResponse>('/api/v1/agents', {
      method: 'POST',
      body: JSON.stringify({ name }),
    });
  }

  async debugAgentCommand(
    agentName: string,
    req: DebugAgentRequest,
  ): Promise<AgentExecutionResponse> {
    return this.request<AgentExecutionResponse>(
      `/api/v1/agents/${encodeURIComponent(agentName)}/debug`,
      {
        method: 'POST',
        body: JSON.stringify(req),
      },
    );
  }

  async getAgentSchema(): Promise<unknown> {
    return this.request<unknown>('/api/v1/schemas/agent');
  }

  async login(username: string, password: string): Promise<LoginResponse> {
    return this.request<LoginResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password } satisfies LoginRequest),
    });
  }

  async logout(): Promise<void> {
    await this.request<void>('/api/v1/auth/logout', { method: 'POST' });
  }

  async getAuthMe(): Promise<MeResponse> {
    return this.request<MeResponse>('/api/v1/auth/me');
  }

  async fetchArtifactContent(processId: number, artifactPath: string): Promise<{
    content: string;
    isComplete: boolean;
    processStatus: string;
    filename: string;
  }> {
    const params = new URLSearchParams({
      processId: String(processId),
      path: artifactPath,
    });

    const url = `${this.baseUrl}/api/v1/artifacts?${params.toString()}`;
    const headers: Record<string, string> = { [CSRF_HEADER]: '1', ...this.authorizationHeader() };

    const response = await fetch(url, { headers, credentials: this.credentials() });

    if (!response.ok) {
      if (response.status === 401 && appConfig.auth.enabled) {
        notifyAuthExpired('http-401');
      }
      throw new HomerApiError(
        `Failed to fetch artifact: ${response.statusText}`,
        response.status,
        response.statusText
      );
    }

    const content = await response.text();

    return {
      content,
      filename: extractFilename(artifactPath),
      isComplete: response.headers.get('X-File-Complete') === 'true',
      processStatus: response.headers.get('X-Process-Status') || 'UNKNOWN',
    };
  }
}

// Strict, not truthy: NaN or 0 must not silently widen a children or flow
// query into the unfiltered list.
function isPositiveId(id: number | undefined): boolean {
  return id !== undefined && Number.isFinite(id) && id > 0;
}

/** The filter part of a process-list query; paging is appended after it. */
function processFilterParams(filters: ProcessFilters | undefined): URLSearchParams {
  const params = new URLSearchParams();

  if (filters?.types && filters.types.length > 0) {
    params.append('types', filters.types.join(','));
  }

  if (filters?.agentName) {
    params.set('agentName', filters.agentName);
  }

  if (isPositiveId(filters?.parentProcessId)) {
    params.set('parentProcessId', String(filters?.parentProcessId));
  }

  if (isPositiveId(filters?.rootProcessId)) {
    params.set('rootProcessId', String(filters?.rootProcessId));
  }

  // Only the literal 'true' is sent — the backend rejects any other value, so
  // an accidental `roots=false` would be a 400 rather than an unfiltered list.
  if (filters?.roots === true) {
    params.set('roots', 'true');
  }

  appendTags(params, filters?.tags);

  return params;
}

// Repeatable: each tag is a separate `tag` param, ANDed by the backend.
function appendTags(params: URLSearchParams, tags: string[] | undefined): void {
  if (tags && tags.length > 0) {
    for (const tag of tags) {
      params.append('tag', tag);
    }
  }
}

/** Wraps a non-HTTP failure; [responded] = the fetch resolved, so `read` threw. */
function toHomerApiError(error: unknown, responded: boolean): HomerApiError {
  if (error instanceof HomerApiError) return error;
  const timedOut = error instanceof Error && error.name === 'AbortError';
  const wrapped = new HomerApiError(
    timedOut ? 'Request timeout' : error instanceof Error ? error.message : 'Unknown error occurred',
  );
  wrapped.failure = timedOut ? 'timeout' : responded ? 'invalid-response' : 'unreachable';
  return wrapped;
}

/** One agent file's endpoint; each `path` segment is encoded, its `/`s kept. */
function agentFilePath(agentName: string, path: string): string {
  const encoded = path
    .split('/')
    .map((s) => encodeURIComponent(s))
    .join('/');
  return `/api/v1/agents/${encodeURIComponent(agentName)}/files/${encoded}`;
}

export const homerApi = new HomerApiClient();
export { HomerApiError };
