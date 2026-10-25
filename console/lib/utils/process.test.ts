import { describe, it, expect } from 'vitest';
import fc from 'fast-check';
import {
  getLastCommand,
  getStatusTime,
  isActiveStatus,
  isRetryableStatus,
  parseProcessListParams,
  PROCESS_STATUS_FILTERS,
} from './process';
import { formatTimestamp } from './format';
import type { ProcessInfo, ProcessExecution } from '@/types/homer';

function makeExecution(overrides: Partial<ProcessExecution> = {}): ProcessExecution {
  return {
    label: 'cmd-0',
    start: 1_700_000_000,
    end: 1_700_000_001,
    resultCode: 0,
    stdOut: 'cmd_0.out',
    stdErr: 'cmd_0.err',
    errMsg: null,
    ...overrides,
  };
}

/** A command recorded as an execution but never run: `when:` said no. */
function skipped(label: string): ProcessExecution {
  return makeExecution({
    label,
    resultCode: -1,
    skipped: true,
    stdOut: null,
    stdErr: null,
    errMsg: 'when.all expression #1 false: false-expr',
  });
}

function makeProcess(overrides: Partial<ProcessInfo> = {}): ProcessInfo {
  return {
    id: 42,
    status: 'FINISHED',
    agentName: 'demo-agent',
    history: [{ timestamp: 1_700_000_002, status: 'FINISHED' }],
    executions: [makeExecution()],
    currentCommand: null,
    currentCommandStart: null,
    purgeAt: null,
    ...overrides,
  };
}

describe('getLastCommand', () => {
  it('reports the current command as running for a WORKING process', () => {
    const process = makeProcess({ status: 'WORKING', currentCommand: 'cmd-running' });
    expect(getLastCommand(process)).toEqual({ status: 'running', label: 'cmd-running' });
  });

  it('classifies a successful last execution (resultCode 0)', () => {
    const process = makeProcess({ executions: [makeExecution({ label: 'ok', resultCode: 0 })] });
    expect(getLastCommand(process)).toEqual({ status: 'success', label: 'ok' });
  });

  it('classifies a failed last execution (non-zero resultCode)', () => {
    const process = makeProcess({ executions: [makeExecution({ label: 'boom', resultCode: 2 })] });
    expect(getLastCommand(process)).toEqual({ status: 'failed', label: 'boom' });
  });

  it('ignores a skipped last execution and names the one before it', () => {
    const process = makeProcess({
      executions: [makeExecution({ label: 'ran', resultCode: 0 }), skipped('skip')],
    });
    expect(getLastCommand(process)).toEqual({ status: 'success', label: 'ran' });
  });

  it('names the last command that ran when trailing commands were skipped', () => {
    // A quickstart run answered `ship`: `hold` and `unexpected-answer` were
    // skipped by their `when:` conditions after `ship` ran.
    const process = makeProcess({
      executions: [
        makeExecution({ label: 'write-report', resultCode: 0 }),
        makeExecution({ label: 'ask-ship-it', resultCode: 0 }),
        makeExecution({ label: 'ship', resultCode: 0 }),
        skipped('hold'),
        skipped('unexpected-answer'),
      ],
    });
    expect(getLastCommand(process)).toEqual({ status: 'success', label: 'ship' });
  });

  it('names `hold` for a run answered hold', () => {
    const process = makeProcess({
      executions: [
        makeExecution({ label: 'ask-ship-it', resultCode: 0 }),
        makeExecution({ label: 'hold', resultCode: 0 }),
        skipped('ship'),
        skipped('unexpected-answer'),
      ],
    });
    expect(getLastCommand(process)).toEqual({ status: 'success', label: 'hold' });
  });

  it('reports a failed last-ran command even when skips follow it', () => {
    const process = makeProcess({
      executions: [makeExecution({ label: 'boom', resultCode: 2 }), skipped('skip')],
    });
    expect(getLastCommand(process)).toEqual({ status: 'failed', label: 'boom' });
  });

  it('returns null status/label when every execution was skipped', () => {
    const process = makeProcess({ executions: [skipped('a'), skipped('b')] });
    expect(getLastCommand(process)).toEqual({ status: null, label: null });
  });

  it('uses the last execution when there are several', () => {
    const process = makeProcess({
      executions: [
        makeExecution({ label: 'first', resultCode: 0 }),
        makeExecution({ label: 'last', resultCode: 3 }),
      ],
    });
    expect(getLastCommand(process)).toEqual({ status: 'failed', label: 'last' });
  });

  it('returns null status/label when there are no executions', () => {
    const process = makeProcess({ executions: [] });
    expect(getLastCommand(process)).toEqual({ status: null, label: null });
  });

  it('falls through to executions when WORKING but no current command', () => {
    const process = makeProcess({
      status: 'WORKING',
      currentCommand: null,
      executions: [makeExecution({ label: 'done', resultCode: 0 })],
    });
    expect(getLastCommand(process)).toEqual({ status: 'success', label: 'done' });
  });
});

describe('getLastCommand (property-based)', () => {
  const execution = fc.record({
    label: fc.string({ minLength: 1, maxLength: 12 }),
    resultCode: fc.integer({ min: -1, max: 5 }),
    skipped: fc.option(fc.boolean(), { nil: undefined }),
  });

  it('never names a skipped command, and names none only when none ran', () => {
    fc.assert(
      fc.property(fc.array(execution, { maxLength: 12 }), (executions) => {
        const last = getLastCommand(
          makeProcess({ executions: executions.map((e) => makeExecution(e)) }),
        );
        const ran = executions.filter((e) => !e.skipped);

        if (ran.length === 0) {
          expect(last).toEqual({ status: null, label: null });
          return;
        }
        const expected = ran[ran.length - 1];
        expect(last.label).toBe(expected.label);
        expect(last.status).toBe(expected.resultCode === 0 ? 'success' : 'failed');
      }),
    );
  });
});

describe('getStatusTime', () => {
  it('formats the most recent history entry timestamp', () => {
    const process = makeProcess({
      history: [
        { timestamp: 1_700_000_000, status: 'CREATED' },
        { timestamp: 1_700_000_500, status: 'FINISHED' },
      ],
    });
    expect(getStatusTime(process)).toBe(formatTimestamp(1_700_000_500));
  });

  it("returns '-' when history is empty", () => {
    const process = makeProcess({ history: [] });
    expect(getStatusTime(process)).toBe('-');
  });
});

describe('isActiveStatus / isRetryableStatus', () => {
  it.each([
    ['CREATED', true, false],
    ['WORKING', true, false],
    ['FINISHED', false, true],
    ['FAILED', false, true],
    ['KILLED', false, true],
    ['UNKNOWN', false, false],
  ] as const)('%s → active %s, retryable %s', (status, active, retryable) => {
    expect(isActiveStatus(status)).toBe(active);
    expect(isRetryableStatus(status)).toBe(retryable);
  });
});

describe('parseProcessListParams', () => {
  const parse = (query: string) => parseProcessListParams(new URLSearchParams(query));

  it('defaults to the unfiltered, newest-first, first page of all runs', () => {
    expect(parse('')).toEqual({
      selectedStatuses: [],
      selectedAgent: '',
      order: 'desc',
      offset: 0,
      parentProcessId: undefined,
      rootsView: false,
      showRoots: false,
      selectedTags: [],
      hasFilters: false,
    });
  });

  it('reads every filter, the sort order and the offset', () => {
    expect(
      parse('status=FAILED,WORKING&agent=demo&order=asc&offset=50&parent=7&tag=a,b'),
    ).toEqual({
      selectedStatuses: ['FAILED', 'WORKING'],
      selectedAgent: 'demo',
      order: 'asc',
      offset: 50,
      parentProcessId: 7,
      rootsView: false,
      showRoots: false,
      selectedTags: ['a', 'b'],
      hasFilters: true,
    });
  });

  it('drops unknown statuses', () => {
    expect(parse('status=FAILED,BOGUS,,working').selectedStatuses).toEqual(['FAILED']);
  });

  it('treats any order but asc as newest first', () => {
    expect(parse('order=ASC').order).toBe('desc');
    expect(parse('order=desc').order).toBe('desc');
  });

  it.each(['abc', '-5', ''])('falls back to offset 0 for %j', (raw) => {
    expect(parse(`offset=${raw}`).offset).toBe(0);
  });

  it.each(['0', '-1', 'x'])('ignores an invalid parent %j', (raw) => {
    expect(parse(`parent=${raw}`).parentProcessId).toBeUndefined();
  });

  it('shows roots only in the roots view without a parent filter', () => {
    expect(parse('view=roots')).toMatchObject({ rootsView: true, showRoots: true, hasFilters: false });
    expect(parse('view=roots&parent=3')).toMatchObject({ rootsView: true, showRoots: false });
    expect(parse('view=all')).toMatchObject({ rootsView: false, showRoots: false });
  });

  it('trims, de-dupes and drops empty tags', () => {
    expect(parse('tag=%20a%20,,b,a,%20').selectedTags).toEqual(['a', 'b']);
  });

  it.each(['status=KILLED', 'agent=x', 'parent=2', 'tag=t'])('counts %s as a filter', (query) => {
    expect(parse(query).hasFilters).toBe(true);
  });

  it('does not count the view, order or offset as a filter', () => {
    expect(parse('view=roots&order=asc&offset=10').hasFilters).toBe(false);
  });
});

describe('parseProcessListParams (property-based)', () => {
  // Values biased towards the separators and number shapes the parser splits on.
  const raw = fc.oneof(
    fc.constantFrom('', ',', ' ', '0', '-1', '1', '1e3', 'NaN', 'asc', 'roots', ...PROCESS_STATUS_FILTERS),
    fc.string({ maxLength: 12 }),
    fc.integer().map(String),
  );
  const keys = ['status', 'agent', 'order', 'offset', 'parent', 'view', 'tag'] as const;
  const query = fc.record(Object.fromEntries(keys.map((k) => [k, fc.option(raw, { nil: undefined })])));

  it('always yields a valid, normalized list state', async () => {
    await fc.assert(
      fc.property(query, (q) => {
        const params = new URLSearchParams();
        for (const [k, v] of Object.entries(q)) if (v !== undefined) params.set(k, v);
        const out = parseProcessListParams(params);

        expect(Number.isInteger(out.offset) && out.offset >= 0).toBe(true);
        if (out.parentProcessId !== undefined) {
          expect(Number.isInteger(out.parentProcessId) && out.parentProcessId >= 1).toBe(true);
        }
        expect(out.selectedStatuses.every((s) => PROCESS_STATUS_FILTERS.includes(s))).toBe(true);
        expect(['asc', 'desc']).toContain(out.order);
        expect(new Set(out.selectedTags).size).toBe(out.selectedTags.length);
        expect(out.selectedTags.every((t) => t.length > 0 && t === t.trim())).toBe(true);
        expect(out.showRoots).toBe(out.rootsView && out.parentProcessId === undefined);
        expect(out.hasFilters).toBe(
          out.selectedStatuses.length > 0 ||
            out.selectedAgent !== '' ||
            out.parentProcessId !== undefined ||
            out.selectedTags.length > 0,
        );
      }),
    );
  });
});
