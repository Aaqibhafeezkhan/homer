import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

vi.mock('@/lib/auth/auth-expired-bus', () => ({
  notifyAuthExpired: vi.fn(),
}));
vi.mock('@/lib/config/app-config', () => ({
  appConfig: {
    api: { timeout: 5000 },
    auth: { enabled: true, mode: 'cookie' },
    pagination: { defaultLimit: 50 },
  },
}));

import { CSRF_HEADER, homerApi, HomerApiError } from './client';
import {
  resetInstancesStoreForTests,
  setActiveInstance,
  setActiveToken,
  upsertInstance,
} from '@/lib/instances/store';
import { notifyAuthExpired } from '@/lib/auth/auth-expired-bus';

describe('HomerApiClient — agent files API', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    globalThis.fetch = vi.fn();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  function mockJson(body: unknown, status = 200): void {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      statusText: status === 200 ? 'OK' : 'Bad',
      clone() { return this; },
      json: async () => body,
    });
  }

  it('listAgentFiles hits the /files collection endpoint', async () => {
    mockJson({ entries: [] });
    await homerApi.listAgentFiles('my-agent');
    const call = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(call[0]).toContain('/api/v1/agents/my-agent/files');
    expect(call[1].method).toBeUndefined();
  });

  it('readAgentFile encodes path segments but preserves slashes', async () => {
    mockJson({ path: 'sub/file.txt', content: 'x' });
    await homerApi.readAgentFile('a', 'sub dir/file name.txt');
    const url = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(url).toContain('/api/v1/agents/a/files/sub%20dir/file%20name.txt');
  });

  it('writeAgentFile sends PUT with JSON body', async () => {
    mockJson({ path: 'agent.yaml', ok: true });
    await homerApi.writeAgentFile('a', 'agent.yaml', 'name: a');
    const [, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(opts.method).toBe('PUT');
    expect(JSON.parse(opts.body)).toEqual({ content: 'name: a' });
  });

  it('deleteAgentFile sends DELETE and tolerates 204 No Content', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true,
      status: 204,
      statusText: 'No Content',
      clone() { return this; },
      json: async () => {
        throw new SyntaxError('Unexpected end of JSON input');
      },
    });
    await expect(homerApi.deleteAgentFile('a', 'file.txt')).resolves.toBeUndefined();
    const [, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(opts.method).toBe('DELETE');
  });

  it('createAgent POSTs /api/v1/agents with name', async () => {
    mockJson({ name: 'new-one' }, 201);
    await homerApi.createAgent('new-one');
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/api/v1/agents');
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body)).toEqual({ name: 'new-one' });
  });

  it('debugAgentCommand POSTs commandIndex + env + queryParams', async () => {
    mockJson({ processId: 42 });
    await homerApi.debugAgentCommand('a', {
      commandIndex: 1,
      env: { FOO: 'bar' },
      queryParams: { p: 'v' },
    });
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/api/v1/agents/a/debug');
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body)).toEqual({
      commandIndex: 1,
      env: { FOO: 'bar' },
      queryParams: { p: 'v' },
    });
  });

  it('attaches the 422 error body to HomerApiError for schema violations', async () => {
    mockJson({ msg: 'Invalid agent', errors: ['bad field'] }, 422);
    await expect(
      homerApi.writeAgentFile('a', 'agent.yaml', 'bad:'),
    ).rejects.toMatchObject({
      status: 422,
      body: { msg: 'Invalid agent', errors: ['bad field'] },
    });
    await expect(
      homerApi.writeAgentFile('a', 'agent.yaml', 'bad:'),
    ).rejects.toBeInstanceOf(HomerApiError);
  });

  it('login POSTs /api/v1/auth/login with credentials:include and username/password body', async () => {
    mockJson({ username: 'alice' });
    await homerApi.login('alice', 'correct-horse');
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/api/v1/auth/login');
    expect(opts.method).toBe('POST');
    expect(opts.credentials).toBe('include');
    expect(JSON.parse(opts.body)).toEqual({ username: 'alice', password: 'correct-horse' });
  });

  it('logout POSTs /api/v1/auth/logout idempotently and tolerates 204', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true,
      status: 204,
      statusText: 'No Content',
      clone() { return this; },
      json: async () => { throw new SyntaxError('Unexpected end of JSON input'); },
    });
    await expect(homerApi.logout()).resolves.toBeUndefined();
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/api/v1/auth/logout');
    expect(opts.method).toBe('POST');
    expect(opts.credentials).toBe('include');
  });

  it('getAuthMe GETs /api/v1/auth/me and returns the principal', async () => {
    mockJson({ username: 'alice', role: 'admin', grants: [] });
    const me = await homerApi.getAuthMe();
    expect(me).toEqual({ username: 'alice', role: 'admin', grants: [] });
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/api/v1/auth/me');
    expect(opts.method).toBeUndefined();
    expect(opts.credentials).toBe('include');
  });

  it('every request carries the X-Homer-CSRF header (ADR-0016)', async () => {
    mockJson({ entries: [] });
    await homerApi.listAgentFiles('a');
    const [, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(opts.headers['X-Homer-CSRF']).toBe('1');
  });

  it('getQuestions GETs /api/v1/questions with status + processId filters', async () => {
    mockJson({ questions: [] });
    await homerApi.getQuestions({ status: 'OPEN', processId: 7 });
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/api/v1/questions?');
    expect(url).toContain('status=OPEN');
    expect(url).toContain('processId=7');
    expect(opts.method).toBeUndefined();
  });

  it('getQuestions without filters hits the bare collection endpoint', async () => {
    mockJson({ questions: [] });
    await homerApi.getQuestions();
    const url = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(url.endsWith('/api/v1/questions')).toBe(true);
  });

  it('answerQuestion POSTs the answer body to /api/v1/questions/{id}/answer', async () => {
    mockJson({ id: 'q-1', status: 'ANSWERED', answer: 'yes' });
    await homerApi.answerQuestion('q-1', 'yes');
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/api/v1/questions/q-1/answer');
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body)).toEqual({ answer: 'yes' });
  });

  it('getProcesses forwards the parentProcessId filter and omits it when unset', async () => {
    mockJson({ processes: [], total: 0, offset: 0, limit: 50 });
    await homerApi.getProcesses({ parentProcessId: 7 });
    const withParent = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(withParent).toContain('/api/v1/status/all?');
    expect(withParent).toContain('parentProcessId=7');

    await homerApi.getProcesses({ agentName: 'echo' });
    const withoutParent = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[1][0];
    expect(withoutParent).not.toContain('parentProcessId');
  });

  it('getProcesses drops a NaN or non-positive parentProcessId', async () => {
    mockJson({ processes: [], total: 0, offset: 0, limit: 50 });
    await homerApi.getProcesses({ parentProcessId: NaN });
    await homerApi.getProcesses({ parentProcessId: 0 });
    const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls;
    expect(calls[0][0]).not.toContain('parentProcessId');
    expect(calls[1][0]).not.toContain('parentProcessId');
  });

  it('getProcesses forwards the rootProcessId filter and omits it when unset', async () => {
    mockJson({ processes: [], total: 0, offset: 0, limit: 50 });
    await homerApi.getProcesses({ rootProcessId: 7 });
    const withRoot = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(withRoot).toContain('/api/v1/status/all?');
    expect(withRoot).toContain('rootProcessId=7');

    await homerApi.getProcesses({ agentName: 'echo' });
    const withoutRoot = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[1][0];
    expect(withoutRoot).not.toContain('rootProcessId');
  });

  it('getProcesses drops a NaN, non-positive or non-finite rootProcessId', async () => {
    mockJson({ processes: [], total: 0, offset: 0, limit: 50 });
    await homerApi.getProcesses({ rootProcessId: NaN });
    await homerApi.getProcesses({ rootProcessId: 0 });
    await homerApi.getProcesses({ rootProcessId: -3 });
    await homerApi.getProcesses({ rootProcessId: Infinity });
    const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls;
    for (const call of calls) {
      expect(call[0]).not.toContain('rootProcessId');
    }
  });

  it('getProcesses sends roots=true only for the literal true', async () => {
    mockJson({ processes: [], total: 0, offset: 0, limit: 50 });
    await homerApi.getProcesses({ roots: true });
    const withRoots = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(withRoots).toContain('roots=true');

    // The backend rejects anything but the literal true/false, so only `true`
    // is ever sent rather than serializing a falsy flag.
    await homerApi.getProcesses({ roots: false });
    await homerApi.getProcesses({ agentName: 'echo' });
    const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls;
    expect(calls[1][0]).not.toContain('roots');
    expect(calls[2][0]).not.toContain('roots');
  });

  it('getProcesses serializes rootProcessId and roots together', async () => {
    mockJson({ processes: [], total: 0, offset: 0, limit: 50 });
    await homerApi.getProcesses({ rootProcessId: 7, roots: true, order: 'asc' });
    const url = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(url).toContain('rootProcessId=7');
    expect(url).toContain('roots=true');
    expect(url).toContain('order=asc');
  });

  it('getProcesses sends types, every tag and the pagination defaults', async () => {
    mockJson({ processes: [], total: 0, offset: 0, limit: 50 });
    await homerApi.getProcesses({ types: ['WORKING', 'FAILED'], tags: ['a', 'b'] });
    const url = new URL((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0][0], 'http://x');
    expect(url.searchParams.get('types')).toBe('WORKING,FAILED');
    expect(url.searchParams.getAll('tag')).toEqual(['a', 'b']);
    expect(url.searchParams.get('order')).toBe('desc');
    expect(url.searchParams.get('limit')).toBe('50');
    expect(url.searchParams.get('offset')).toBe('0');
  });

  it('getProcesses omits empty types and tags and honours explicit paging', async () => {
    mockJson({ processes: [], total: 0, offset: 0, limit: 50 });
    await homerApi.getProcesses({ types: [], tags: [], limit: 10, offset: 20 });
    await homerApi.getProcesses();
    const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls;
    const paged = new URL(calls[0][0], 'http://x');
    expect(paged.searchParams.has('types')).toBe(false);
    expect(paged.searchParams.has('tag')).toBe(false);
    expect(paged.searchParams.get('limit')).toBe('10');
    expect(paged.searchParams.get('offset')).toBe('20');
    expect(new URL(calls[1][0], 'http://x').search).toBe('?order=desc&limit=50&offset=0');
  });

  it('getContinuations forwards the status and agentName filters', async () => {
    mockJson({ continuations: [] });
    await homerApi.getContinuations({ status: 'FAILED', agentName: 'echo' });
    const url = new URL((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0][0], 'http://x');
    expect(url.pathname).toBe('/api/v1/continuations');
    expect(url.searchParams.get('status')).toBe('FAILED');
    expect(url.searchParams.get('agentName')).toBe('echo');
  });

  it('getContinuations without filters hits the bare collection endpoint', async () => {
    mockJson({ continuations: [] });
    await homerApi.getContinuations();
    const url = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(url.endsWith('/api/v1/continuations')).toBe(true);
  });

  it('executeAgent POSTs the JSON body with non-blank query params to the encoded agent path', async () => {
    mockJson({ processId: 9 });
    await expect(
      homerApi.executeAgent('my agent', {
        queryParams: { ticket: 'ABC-1', blank: '   ', empty: '' },
        body: { foo: 'bar' },
        headers: { 'Content-Type': 'text/plain', 'X-Trace': 't1' },
      }),
    ).resolves.toEqual({ processId: 9 });
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/agent\/my%20agent\?ticket=ABC-1$/);
    expect(opts.method).toBe('POST');
    expect(JSON.parse(opts.body)).toEqual({ foo: 'bar' });
    // A body forces the JSON content type over a caller-supplied one.
    expect(opts.headers['Content-Type']).toBe('application/json');
    expect(opts.headers['X-Trace']).toBe('t1');
    expect(opts.headers[CSRF_HEADER]).toBe('1');
  });

  it('executeAgent sends no body and no query string when both are empty', async () => {
    mockJson({ processId: 10 });
    await homerApi.executeAgent('a', {
      queryParams: { only: ' ' },
      body: {},
      headers: { 'Content-Type': 'text/plain' },
    });
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/agent\/a$/);
    expect(opts.body).toBeUndefined();
    // Without a body the caller's headers are left as given.
    expect(opts.headers['Content-Type']).toBe('text/plain');
  });

  it('executeAgent tolerates omitted query params, body and headers', async () => {
    mockJson({ processId: 11 });
    await homerApi.executeAgent('a', { body: null });
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toMatch(/\/api\/v1\/agent\/a$/);
    expect(opts.method).toBe('POST');
    expect(opts.body).toBeUndefined();
    expect(opts.headers['Content-Type']).toBe('application/json');
  });

  it('killProcess issues a DELETE to /api/v1/kill/{id}', async () => {
    mockJson({ status: 'KILLED' });
    await homerApi.killProcess(42);
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/api/v1/kill/42');
    expect(opts.method).toBe('DELETE');
    expect(opts.headers['X-Homer-CSRF']).toBe('1');
  });

  it('cancelDispatch issues a DELETE to /api/v1/dispatches/{id} and tolerates 204', async () => {
    // request() short-circuits a 204 to undefined without parsing the body,
    // so the mock fails loudly if that branch is ever rerouted through json().
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true,
      status: 204,
      statusText: 'No Content',
      clone() { return this; },
      json: async () => {
        throw new Error('json must not be called for a 204');
      },
    });
    await expect(homerApi.cancelDispatch(3)).resolves.toBeUndefined();
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/api/v1/dispatches/3');
    expect(opts.method).toBe('DELETE');
  });

  it('getProcessLangGraph fetches /api/v1/processes/{id}/langgraph with no query string', async () => {
    mockJson({ label: 'Deploy', module: 'deploy.graph', threadId: 't-1' });
    await homerApi.getProcessLangGraph(42);
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/api/v1/processes/42/langgraph');
    expect(url).not.toContain('?');
    expect(opts.method).toBeUndefined();
  });

  it('getProcessLangGraph appends ?graph=true when requested', async () => {
    mockJson({
      label: 'Deploy',
      module: 'deploy.graph',
      threadId: 't-1',
      topology: { nodes: [{ id: 'a' }], edges: [] },
    });
    await homerApi.getProcessLangGraph(42, { graph: true });
    const url = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(url).toContain('/api/v1/processes/42/langgraph?graph=true');
  });

  it('resumeProcess issues a POST to /api/v1/processes/{id}/resume', async () => {
    mockJson({ processId: 99 });
    await homerApi.resumeProcess(42);
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/api/v1/processes/42/resume');
    expect(opts.method).toBe('POST');
  });

  it('fireAgentCron POSTs /api/v1/agents/{name}/cron-run and tolerates 204', async () => {
    // request() short-circuits a 204 to undefined without parsing the body,
    // so the mock fails loudly if that branch is ever rerouted through json().
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true,
      status: 204,
      statusText: 'No Content',
      clone() { return this; },
      json: async () => {
        throw new Error('json must not be called for a 204');
      },
    });
    await expect(homerApi.fireAgentCron('my agent')).resolves.toBeUndefined();
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/api/v1/agents/my%20agent/cron-run');
    expect(opts.method).toBe('POST');
    expect(opts.headers['X-Homer-CSRF']).toBe('1');
  });

  it('downloadArtifact (blob path) carries the X-Homer-CSRF header', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      clone() { return this; },
      blob: async () => new Blob(['x']),
      headers: new Headers({ 'X-File-Complete': 'true', 'X-Process-Status': 'FINISHED' }),
    });
    await homerApi.downloadArtifact(7, 'out.txt');
    const [, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(opts.headers['X-Homer-CSRF']).toBe('1');
  });

  it('fetchArtifactContent carries the X-Homer-CSRF header', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      clone() { return this; },
      text: async () => 'body',
      headers: new Headers({ 'X-File-Complete': 'true', 'X-Process-Status': 'FINISHED' }),
    });
    await homerApi.fetchArtifactContent(7, 'out.txt');
    const [, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(opts.headers['X-Homer-CSRF']).toBe('1');
  });

  it('getCosts hits /api/v1/costs and returns the parsed snapshot', async () => {
    mockJson({
      globalUsdLast60s: 1.23,
      globalCapUsdPerMin: 5,
      perAgentUsdLast24h: { reviewer: 0.5 },
      perAgentCapUsdPerDay: { reviewer: 10 },
      snapshotAtSec: 1700000000,
    });
    const result = await homerApi.getCosts();
    const [url, opts] = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0];
    expect(url).toContain('/api/v1/costs');
    expect(opts.method).toBeUndefined();
    expect(result).toEqual({
      globalUsdLast60s: 1.23,
      globalCapUsdPerMin: 5,
      perAgentUsdLast24h: { reviewer: 0.5 },
      perAgentCapUsdPerDay: { reviewer: 10 },
      snapshotAtSec: 1700000000,
    });
  });
});

describe('HomerApiClient — 401 auth-expired bus', () => {
  const originalFetch = globalThis.fetch;

  beforeEach(() => {
    globalThis.fetch = vi.fn();
    vi.mocked(notifyAuthExpired).mockClear();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  function mock401(): void {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: false,
      status: 401,
      statusText: 'Unauthorized',
      clone() { return this; },
      json: async () => ({}),
    });
  }

  it('fires the bus on 401 from a data endpoint', async () => {
    mock401();
    await expect(homerApi.getCosts()).rejects.toThrow(HomerApiError);
    expect(vi.mocked(notifyAuthExpired)).toHaveBeenCalledWith('http-401');
  });

  it('does not fire the bus on 401 from the auth probe (/auth/me)', async () => {
    mock401();
    await expect(homerApi.getAuthMe()).rejects.toThrow(HomerApiError);
    expect(vi.mocked(notifyAuthExpired)).not.toHaveBeenCalled();
  });

  it('does not fire the bus on 401 from login (wrong credentials)', async () => {
    mock401();
    await expect(homerApi.login('u', 'p')).rejects.toThrow(HomerApiError);
    expect(vi.mocked(notifyAuthExpired)).not.toHaveBeenCalled();
  });

  it('fires the bus on 401 from a blob download', async () => {
    mock401();
    await expect(homerApi.downloadArtifact(1, 'out.txt')).rejects.toMatchObject({
      message: 'API request failed: Unauthorized',
      status: 401,
    });
    expect(vi.mocked(notifyAuthExpired)).toHaveBeenCalledWith('http-401');
  });

  it('does not fire the bus on a non-401 failure of a blob download', async () => {
    (globalThis.fetch as ReturnType<typeof vi.fn>).mockResolvedValue({
      ok: false,
      status: 404,
      statusText: 'Not Found',
    });
    await expect(homerApi.downloadArtifact(1, 'out.txt')).rejects.toMatchObject({
      message: 'API request failed: Not Found',
      status: 404,
    });
    expect(vi.mocked(notifyAuthExpired)).not.toHaveBeenCalled();
  });
});

describe('HomerApiClient — auth headers and error mapping', () => {
  const originalFetch = globalThis.fetch;
  const fetchMock = () => globalThis.fetch as ReturnType<typeof vi.fn>;
  const sentHeaders = () => fetchMock().mock.calls[0][1].headers as Record<string, string>;
  const sentCredentials = () => fetchMock().mock.calls[0][1].credentials;

  beforeEach(() => {
    resetInstancesStoreForTests();
    localStorage.clear();
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      headers: new Headers({ 'X-Process-Status': 'FINISHED', 'X-File-Complete': 'true' }),
      clone() { return this; },
      json: async () => ({ entries: [] }),
      text: async () => 'body',
      blob: async () => new Blob(['x']),
    });
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    resetInstancesStoreForTests();
    localStorage.clear();
    vi.restoreAllMocks();
  });

  function useBearerInstance(token: string): void {
    upsertInstance({ id: 'b', label: 'B', baseUrl: 'http://b.test', mode: 'bearer' });
    setActiveInstance('b');
    setActiveToken(token);
  }

  const calls: Array<[string, () => Promise<unknown>]> = [
    ['request', () => homerApi.listAgentFiles('a')],
    ['requestBlob', () => homerApi.downloadArtifact(1, 'out.txt')],
    ['fetchArtifactContent', () => homerApi.fetchArtifactContent(1, 'out.txt')],
  ];

  it.each(calls)('%s sends the bearer token and no cookie credentials in bearer mode', async (_, call) => {
    useBearerInstance('tok-1');
    await call();
    expect(sentHeaders()['Authorization']).toBe('Bearer tok-1');
    expect(sentHeaders()[CSRF_HEADER]).toBe('1');
    expect(sentCredentials()).toBeUndefined();
  });

  it.each(calls)('%s sends no Authorization header in cookie mode', async (_, call) => {
    await call();
    expect(sentHeaders()['Authorization']).toBeUndefined();
    expect(sentCredentials()).toBe('include');
  });

  it('maps an aborted request to a timeout error', async () => {
    fetchMock().mockRejectedValue(Object.assign(new Error('aborted'), { name: 'AbortError' }));
    await expect(homerApi.listAgentFiles('a')).rejects.toMatchObject({ message: 'Request timeout' });
    await expect(homerApi.downloadArtifact(1, 'out.txt')).rejects.toMatchObject({
      message: 'Request timeout',
    });
  });

  it('aborts a request that outlives the client timeout', async () => {
    vi.useFakeTimers();
    try {
      fetchMock().mockImplementation(
        (_url: string, init: RequestInit) =>
          new Promise((_resolve, reject) => {
            init.signal?.addEventListener('abort', () =>
              reject(Object.assign(new Error('aborted'), { name: 'AbortError' })),
            );
          }),
      );
      const pending = homerApi.downloadArtifact(1, 'out.txt').catch((e) => e);
      await vi.advanceTimersByTimeAsync(5000);
      expect(await pending).toMatchObject({ message: 'Request timeout' });
    } finally {
      vi.useRealTimers();
    }
  });

  it('maps a network failure to a HomerApiError carrying its message', async () => {
    fetchMock().mockRejectedValue(new TypeError('Failed to fetch'));
    const err = await homerApi.listAgentFiles('a').catch((e) => e);
    expect(err).toBeInstanceOf(HomerApiError);
    expect(err.message).toBe('Failed to fetch');
  });

  it('rejects a failed response whose body is not JSON with an error carrying no body', async () => {
    fetchMock().mockResolvedValue({
      ok: false,
      status: 502,
      statusText: 'Bad Gateway',
      clone() { return this; },
      json: async () => { throw new SyntaxError('Unexpected token <'); },
    });
    const err = await homerApi.listAgentFiles('a').catch((e) => e);
    expect(err).toBeInstanceOf(HomerApiError);
    expect(err).toMatchObject({ message: 'API request failed: Bad Gateway', status: 502, body: undefined });
  });

  it('maps a non-Error rejection to the unknown-error message', async () => {
    fetchMock().mockRejectedValue('boom');
    await expect(homerApi.downloadArtifact(1, 'out.txt')).rejects.toMatchObject({
      message: 'Unknown error occurred',
    });
  });
});
