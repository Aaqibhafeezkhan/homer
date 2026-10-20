import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import fc from 'fast-check';
import {
  useLlmEventStream,
  clipLlmEvent,
  boundedStringSize,
  walkedStringSize,
  MAX_LLM_EVENTS,
  MAX_LLM_EVENT_CHARS,
  MAX_LLM_BUFFER_CHARS,
} from './streams';
import type { LlmEvent } from '@/types/homer';

interface CapturedHandlers {
  onEvent: (name: string, data: unknown, id?: string) => void;
}

const { openStreamMock, closeMock } = vi.hoisted(() => ({
  openStreamMock: vi.fn(),
  closeMock: vi.fn(),
}));

vi.mock('./sse', () => ({
  openStream: (...args: unknown[]) => openStreamMock(...args),
}));

function capturedHandlers(): CapturedHandlers {
  return openStreamMock.mock.calls[openStreamMock.mock.calls.length - 1][1] as CapturedHandlers;
}

// The hook publishes to React state at most once per animation frame (CWE-400);
// tests that assert on `result.current` after firing events must let one frame
// run first.
function flushFrame(): Promise<void> {
  return new Promise((resolve) => requestAnimationFrame(() => resolve()));
}

// Walks a spied `JSON.stringify` argument looking for a string longer than `cap`
// without serializing it — the copy the fix must avoid is what we are detecting.
function holdsStringLongerThan(value: unknown, cap: number, depth = 0): boolean {
  if (depth > 3) return false;
  if (typeof value === 'string') return value.length > cap;
  if (Array.isArray(value)) return value.some((v) => holdsStringLongerThan(v, cap, depth + 1));
  if (value && typeof value === 'object') {
    return Object.values(value as Record<string, unknown>).some((v) => holdsStringLongerThan(v, cap, depth + 1));
  }
  return false;
}

describe('useLlmEventStream', () => {
  beforeEach(() => {
    openStreamMock.mockReset();
    openStreamMock.mockReturnValue(closeMock);
  });

  it('appends frames in order and dedupes a replayed frame', async () => {
    const { result } = renderHook(() => useLlmEventStream(7, 0, true));
    const { onEvent } = capturedHandlers();

    const start: LlmEvent = { type: 'session_start', sessionId: 's', model: 'm', tools: [] };
    const msg: LlmEvent = { type: 'assistant_message', messageId: 'm1', model: null, text: 'hi', subagent: false };

    await act(async () => {
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: start }, '0:0:10:0');
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: msg }, '0:0:10:1');
      // reconnect replay of the very first frame
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: start }, '0:0:10:0');
      await flushFrame();
    });

    expect(result.current.events.map((e) => e.type)).toEqual(['session_start', 'assistant_message']);
  });

  it('keeps two sibling events parsed from the same line (same lineOffset, distinct eventIndex)', async () => {
    const { result } = renderHook(() => useLlmEventStream(7, 0, true));
    const { onEvent } = capturedHandlers();

    const a: LlmEvent = { type: 'transient_error', message: 'a' };
    const b: LlmEvent = { type: 'transient_error', message: 'b' };

    await act(async () => {
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: a }, '0:0:20:0');
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: b }, '0:0:20:1');
      await flushFrame();
    });

    expect(result.current.events.map((e) => (e as { message: string }).message)).toEqual(['a', 'b']);
  });

  it('resets the buffer when a newer attempt starts streaming', async () => {
    const { result } = renderHook(() => useLlmEventStream(7, 0, true));
    const { onEvent } = capturedHandlers();

    const first: LlmEvent = { type: 'assistant_message', messageId: 'm1', model: null, text: 'attempt 0', subagent: false };
    const retried: LlmEvent = { type: 'transient_error', message: 'retrying' };

    await act(async () => {
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: first }, '0:0:10:0');
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 1, event: retried }, '0:1:5:0');
      await flushFrame();
    });

    expect(result.current.events.map((e) => e.type)).toEqual(['transient_error']);
  });

  it('appends a frame with no parseable id as-is and marks the stream ended on llm.end', async () => {
    const { result } = renderHook(() => useLlmEventStream(7, 0, true));
    const { onEvent } = capturedHandlers();

    const event: LlmEvent = { type: 'transient_error', message: 'no id' };
    await act(async () => {
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event });
      await flushFrame();
    });
    expect(result.current.events).toHaveLength(1);

    await act(async () => {
      onEvent('llm.end', {});
      await flushFrame();
    });
    expect(result.current.ended).toBe(true);
  });

  it('appends a frame whose id has a non-numeric part as-is without moving the dedupe cursor', async () => {
    const { result } = renderHook(() => useLlmEventStream(7, 0, true));
    const { onEvent } = capturedHandlers();

    const first: LlmEvent = { type: 'transient_error', message: 'first' };
    const odd: LlmEvent = { type: 'transient_error', message: 'odd id' };
    await act(async () => {
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: first }, '0:0:10:0');
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: odd }, '0:0:x:0');
      // still a replay of the first frame: the odd id did not move the cursor
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: first }, '0:0:10:0');
      await flushFrame();
    });

    expect(result.current.events.map((e) => (e as { message: string }).message)).toEqual(['first', 'odd id']);
  });

  it('caps the buffer at MAX_LLM_EVENTS, dropping the oldest and flagging truncated', async () => {
    const { result } = renderHook(() => useLlmEventStream(7, 0, true));
    const { onEvent } = capturedHandlers();

    await act(async () => {
      for (let i = 0; i <= MAX_LLM_EVENTS; i++) {
        const event: LlmEvent = { type: 'transient_error', message: `e${i}` };
        onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event }, `0:0:${i}:0`);
      }
      await flushFrame();
    });

    expect(result.current.events).toHaveLength(MAX_LLM_EVENTS);
    expect((result.current.events[0] as { message: string }).message).toBe('e1');
    expect((result.current.events[MAX_LLM_EVENTS - 1] as { message: string }).message).toBe(`e${MAX_LLM_EVENTS}`);
    expect(result.current.truncated).toBe(true);
  });

  it('bf4839ea CWE-400 replaces an oversized tool input with a note and keeps small events whole', () => {
    const small: LlmEvent = { type: 'tool_call', id: 't1', name: 'Bash', input: { command: 'ls' }, messageId: null };
    expect(clipLlmEvent(small).event).toEqual(small);

    const big: LlmEvent = { type: 'tool_call', id: 't2', name: 'Bash', input: { command: 'x'.repeat(MAX_LLM_EVENT_CHARS) }, messageId: null };
    const { event, size } = clipLlmEvent(big);
    expect(event.type).toBe('tool_call');
    expect((event as { id: string }).id).toBe('t2');
    expect(String((event as { input: unknown }).input)).toContain('too large to show here');
    expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
  });

  it('bf4839ea CWE-400 turns an event oversized outside its payload into a transient-error note', () => {
    const big: LlmEvent = { type: 'tool_call', id: 'i'.repeat(MAX_LLM_EVENT_CHARS), name: 'Bash', input: {}, messageId: null };
    const { event, size } = clipLlmEvent(big);
    expect(event.type).toBe('transient_error');
    expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
  });

  it('CWE-400 clips a multi-MiB payload without ever serializing it whole', () => {
    const stringifySpy = vi.spyOn(JSON, 'stringify');
    try {
      const huge = 'x'.repeat(4 * 1024 * 1024);
      const call: LlmEvent = { type: 'tool_call', id: 't1', name: 'Bash', input: { command: huge }, messageId: null };
      const message: LlmEvent = { type: 'assistant_message', messageId: 'm1', model: null, text: huge, subagent: false };

      for (const event of [call, message]) {
        const { event: clipped, size } = clipLlmEvent(event);
        expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
        expect(JSON.stringify(clipped).length).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
      }

      // The denial path: the oversized string must never reach JSON.stringify as
      // part of an unclipped event — the whole point of reducing before measuring.
      const reachedHuge = stringifySpy.mock.calls.some(([arg]) => holdsStringLongerThan(arg, MAX_LLM_EVENT_CHARS));
      expect(reachedHuge).toBe(false);
    } finally {
      stringifySpy.mockRestore();
    }
  });

  it('CWE-400 clips each oversized payload field and keeps a small event of every kind unchanged', () => {
    const huge = 'x'.repeat(MAX_LLM_EVENT_CHARS + 1);

    const byField: LlmEvent[] = [
      { type: 'tool_call', id: 't1', name: 'Bash', input: { command: huge }, messageId: null },
      { type: 'tool_result', id: 't1', output: { stdout: huge }, isError: false },
      { type: 'assistant_message', messageId: 'm1', model: null, text: huge, subagent: false },
      { type: 'turn_end', status: { type: 'success' }, usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, reasoning: 0 }, costUsd: null, model: null, durationMs: null, durationApiMs: null, numTurns: 1, resultText: huge },
      { type: 'session_start', sessionId: 's', model: 'm', tools: [huge] },
    ];
    for (const event of byField) {
      const { event: clipped, size } = clipLlmEvent(event);
      expect(clipped.type).toBe(event.type);
      expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
      expect(JSON.stringify(clipped)).not.toContain(huge);
    }

    const smalls: LlmEvent[] = [
      { type: 'session_start', sessionId: 's', model: 'm', tools: ['Bash'] },
      { type: 'assistant_message', messageId: 'm1', model: null, text: 'hi', subagent: false },
      { type: 'tool_call', id: 't1', name: 'Bash', input: { command: 'ls' }, messageId: null },
      { type: 'tool_result', id: 't1', output: 'ok', isError: false },
      { type: 'turn_end', status: { type: 'success' }, usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, reasoning: 0 }, costUsd: null, model: null, durationMs: null, durationApiMs: null, numTurns: 1, resultText: 'done' },
      { type: 'transient_error', message: 'boom' },
    ];
    for (const event of smalls) {
      expect(clipLlmEvent(event).event).toEqual(event);
    }
  });

  it('CWE-400 falls back to a transient-error note when an id, name or messageId is oversized', () => {
    const huge = 'i'.repeat(MAX_LLM_EVENT_CHARS);
    const cases: LlmEvent[] = [
      { type: 'tool_call', id: huge, name: 'Bash', input: {}, messageId: null },
      { type: 'tool_call', id: 't1', name: huge, input: {}, messageId: null },
      { type: 'assistant_message', messageId: huge, model: null, text: 'hi', subagent: false },
    ];
    for (const event of cases) {
      const { event: clipped, size } = clipLlmEvent(event);
      expect(clipped.type).toBe('transient_error');
      expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
    }
  });

  it('CWE-400 falls back to a transient-error note when the payload and an id are both oversized', () => {
    const huge = 'x'.repeat(MAX_LLM_EVENT_CHARS + 1);
    const event: LlmEvent = { type: 'tool_call', id: huge, name: 'Bash', input: { command: huge }, messageId: null };
    const { event: clipped, size } = clipLlmEvent(event);
    expect(clipped.type).toBe('transient_error');
    expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
  });

  it('CWE-400 keeps the typed event when the payload is exactly MAX_LLM_EVENT_CHARS of JSON', () => {
    // A payload at the cap itself takes the note (its JSON punctuation no longer
    // fits alongside the event's own), but the event keeps its kind — it must not
    // degrade to a generic transient-error note.
    const payload = 'x'.repeat(MAX_LLM_EVENT_CHARS - 2); // + 2 quotes = exactly the cap
    const tools = ['x'.repeat(MAX_LLM_EVENT_CHARS - 5)]; // brackets + comma = exactly the cap
    const cases: LlmEvent[] = [
      { type: 'tool_call', id: 't1', name: 'Bash', input: payload, messageId: null },
      { type: 'tool_result', id: 't1', output: payload, isError: false },
      { type: 'assistant_message', messageId: 'm1', model: null, text: payload, subagent: false },
      { type: 'turn_end', status: { type: 'success' }, usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, reasoning: 0 }, costUsd: null, model: null, durationMs: null, durationApiMs: null, numTurns: 1, resultText: payload },
      { type: 'session_start', sessionId: 's', model: 'm', tools },
    ];
    for (const event of cases) {
      const { event: clipped, size } = clipLlmEvent(event);
      expect(clipped.type).toBe(event.type);
      expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
      if (clipped.type === 'session_start') {
        // tools cannot carry the note (a string[]), so they are emptied instead.
        expect(clipped.tools).toEqual([]);
      } else {
        const field = clipped.type === 'tool_call' ? clipped.input
          : clipped.type === 'tool_result' ? clipped.output
          : clipped.type === 'assistant_message' ? clipped.text
          : (clipped as { resultText: string | null }).resultText;
        expect(String(field)).toContain('too large to show here');
      }
    }
  });

  it('CWE-400 clips a payload nested past MAX_LLM_SIZE_DEPTH without overflowing the stack', () => {
    // 257 nested containers around a small string: the walk gives up at depth 256
    // and reports the value oversized rather than recursing further.
    let value: unknown = 'deep';
    for (let i = 0; i < 257; i++) value = { nested: value };
    const event: LlmEvent = { type: 'tool_call', id: 't1', name: 'Bash', input: value, messageId: null };
    const { event: clipped, size } = clipLlmEvent(event);
    expect(clipped.type).toBe('tool_call');
    expect((clipped as { input: unknown }).input).toContain('too large to show here');
    expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
  });

  it('CWE-400 counts JSON punctuation, so a payload of nulls and empty containers cannot skip clipping', () => {
    // serialized JSON is far larger than the cap while a naive per-value sum
    // reads 0 for every entry.
    const nulls: Record<string, unknown> = {};
    for (let i = 0; i < 40_000; i++) nulls[`k${i}`] = null;
    const empties: Record<string, unknown> = {};
    for (let i = 0; i < 20_000; i++) empties[`k${i}`] = i % 2 ? {} : [];
    for (const payload of [nulls, empties]) {
      const event: LlmEvent = { type: 'tool_result', id: 't1', output: payload, isError: false };
      const { event: clipped, size } = clipLlmEvent(event);
      expect(JSON.stringify(event).length).toBeGreaterThan(MAX_LLM_EVENT_CHARS);
      expect(clipped.type).toBe('tool_result');
      const note = String((clipped as { output: unknown }).output);
      expect(note).toContain('too large to show here');
      expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
      // The note reports the walk's accumulated count at the abort — past the
      // cap, never an undercount like `[0 characters…]`.
      const reported = Number(note.slice(1, note.indexOf(' ')));
      expect(reported).toBeGreaterThanOrEqual(MAX_LLM_EVENT_CHARS);
    }
  });

  it('CWE-400 aborts the walk early on a wide object and a huge key string', () => {
    // Keys alone past the cap: the walk must bail on the first key, not sum them all.
    const keyed: Record<string, unknown> = { [`k`.repeat(MAX_LLM_EVENT_CHARS)]: 'v' };
    keyed.a = 'b';
    const keyEvent: LlmEvent = { type: 'tool_call', id: 't1', name: 'Bash', input: keyed, messageId: null };
    const keyClipped = clipLlmEvent(keyEvent);
    expect(keyClipped.size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
    expect((keyClipped.event as { input: unknown }).input).toContain('too large to show here');

    // A million small entries: the walk stops once the cap is passed, so this is
    // fast and lands on the note rather than walking every element.
    const wide = Array.from({ length: 1_000_000 }, () => 'x');
    const wideEvent: LlmEvent = { type: 'tool_result', id: 't1', output: wide, isError: false };
    const wideClipped = clipLlmEvent(wideEvent);
    expect(wideClipped.size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
    expect((wideClipped.event as { output: unknown }).output).toContain('too large to show here');
  });

  it('CWE-400 falls back to a transient-error note when a tool_result id or turn_end scalar is oversized', () => {
    const huge = 'i'.repeat(MAX_LLM_EVENT_CHARS);
    const cases: LlmEvent[] = [
      { type: 'tool_result', id: huge, output: 'ok', isError: false },
      { type: 'assistant_message', messageId: 'm1', model: huge, text: 'hi', subagent: false },
      { type: 'session_start', sessionId: huge, model: 'm', tools: [] },
      { type: 'session_start', sessionId: 's', model: huge, tools: [] },
      { type: 'turn_end', status: { type: 'success' }, usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, reasoning: 0 }, costUsd: null, model: huge, durationMs: null, durationApiMs: null, numTurns: 1, resultText: 'done' },
      { type: 'turn_end', status: { type: 'failed', reason: huge }, usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, reasoning: 0 }, costUsd: null, model: null, durationMs: null, durationApiMs: null, numTurns: 1, resultText: null },
      { type: 'turn_end', status: { type: 'stopped', reason: huge }, usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, reasoning: 0 }, costUsd: null, model: null, durationMs: null, durationApiMs: null, numTurns: 1, resultText: null },
    ];
    for (const event of cases) {
      const { event: clipped, size } = clipLlmEvent(event);
      expect(clipped.type).toBe('transient_error');
      expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
    }
  });

  it('CWE-400 keeps escaped characters from under-counting: an escape-inflated payload is still clipped', () => {
    // Each character serializes as a >1-char JSON escape, so a payload whose
    // `.length` is below the cap has a serialized JSON past it; a per-value walk
    // that counted `.length` would let every one of these through. One row per
    // escape class of `boundedStringSize`, built from explicit escapes so no
    // raw control byte can be normalized or stripped out from under the test.
    const escaped: [string, number][] = [
      ["\u0007", 6], // control character escapes as a 6-char \uXXXX
      ["\b", 2],     // short escapes serialize as 2 chars
      ["\f", 2],
      ["\n", 2],
      ["\uD800", 6], // lone surrogate escapes as a 6-char \uXXXX
    ];
    for (const [unit, perChar] of escaped) {
      const text = unit.repeat(Math.ceil(MAX_LLM_EVENT_CHARS / perChar) + 10);
      // The raw `.length` is under the cap — only the counted serialized size
      // catches this payload, which is exactly the under-count class at stake.
      expect(text.length).toBeLessThan(MAX_LLM_EVENT_CHARS);
      const event: LlmEvent = { type: 'assistant_message', messageId: 'm1', model: null, text, subagent: false };
      const { event: clipped, size } = clipLlmEvent(event);
      expect(clipped.type).toBe('assistant_message');
      expect((clipped as { text: string }).text).toContain('too large to show here');
      expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
    }
  });

  it('CWE-400 does not over-count a surrogate pair: a pair payload under the cap is kept', () => {
    // A surrogate pair serializes as the pair itself (2 units -> 2 chars), so it
    // must not be reported as inflated and clipped while it still fits; leave
    // headroom for the event's JSON overhead so the whole event stays under the cap.
    const text = '😀'.repeat(Math.floor((MAX_LLM_EVENT_CHARS - 500) / 2));
    expect(text.length).toBeLessThan(MAX_LLM_EVENT_CHARS);
    expect(JSON.stringify({ text }).length).toBeLessThan(MAX_LLM_EVENT_CHARS);
    const event: LlmEvent = { type: 'assistant_message', messageId: 'm1', model: null, text, subagent: false };
    const { event: clipped, size } = clipLlmEvent(event);
    expect(clipped.type).toBe('assistant_message');
    expect((clipped as { text: string }).text).toBe(text);
    expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
  });

  it('CWE-400 counts a quote or backslash as its two-character escape in the pre-pass', () => {
    // Half the cap of `"` or `\\` serializes past it: the pre-pass must see that
    // and clip, so the oversized original never reaches JSON.stringify whole.
    for (const unit of ['"', '\\']) {
      const text = unit.repeat(MAX_LLM_EVENT_CHARS / 2 + 10);
      expect(text.length).toBeLessThan(MAX_LLM_EVENT_CHARS);
      const event: LlmEvent = { type: 'assistant_message', messageId: 'm1', model: null, text, subagent: false };
      const stringifySpy = vi.spyOn(JSON, 'stringify');
      try {
        const { event: clipped, size } = clipLlmEvent(event);
        expect((clipped as { text: string }).text).toContain('too large to show here');
        expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
        expect(stringifySpy.mock.calls.some(([arg]) => arg === event)).toBe(false);
      } finally {
        stringifySpy.mockRestore();
      }
    }
  });

  it('CWE-400 skips what JSON.stringify skips: inherited keys and function values', () => {
    // An inherited property and a function value never reach the serialized
    // form, so a huge one must not get a fitting event clipped.
    const inherited = Object.create({ hidden: 'x'.repeat(MAX_LLM_EVENT_CHARS) }) as Record<string, unknown>;
    inherited.command = 'ls';
    const withFunction = { command: 'ls', run: () => 'x'.repeat(MAX_LLM_EVENT_CHARS) };
    for (const input of [inherited, withFunction]) {
      const event: LlmEvent = { type: 'tool_call', id: 't1', name: 'Bash', input, messageId: null };
      expect(clipLlmEvent(event)).toEqual({ event, size: JSON.stringify(event).length });
    }
  });

  it('falls back to a transient-error note when the serialized form outgrows what the walk measured', () => {
    // A toJSON hook is invisible to the in-place walk but drives JSON.stringify,
    // so only the final serialization finds the event oversized.
    const event = {
      type: 'tool_result',
      id: 't1',
      output: 'ok',
      toJSON: () => ({ big: 'x'.repeat(MAX_LLM_EVENT_CHARS) }),
    } as unknown as LlmEvent;
    const { event: clipped, size } = clipLlmEvent(event);
    expect(clipped.type).toBe('transient_error');
    expect((clipped as { message: string }).message).toMatch(/^\[\d+ characters, too large to show here/);
    expect(size).toBe(JSON.stringify(clipped).length);
    expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
  });

  it('CWE-400 turns an unknown or absent event discriminator into a transient-error note', () => {
    // The frame's event is only cast to LlmEvent on arrival: a newer server or a
    // malformed frame can carry a discriminator (or none) that no clipping arm
    // matches. Unknown-kind and type-less frames must land on the same
    // transient-error note as any other oversized event, never serialize whole.
    const huge = 'x'.repeat(4 * 1024 * 1024);
    const unknown = { type: 'future_kind', input: huge } as unknown as LlmEvent;
    const typeless = { input: huge } as unknown as LlmEvent;
    for (const event of [unknown, typeless]) {
      const { event: clipped, size } = clipLlmEvent(event);
      expect(clipped.type).toBe('transient_error');
      expect((clipped as { message: string }).message).toContain('too large to show here');
      expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
      expect(JSON.stringify(clipped)).not.toContain(huge);
    }
  });

  it('CWE-400 keeps every oversized event within the cap without serializing it whole', () => {
    // The pre-pass measures the whole event, so a multi-MiB value in any position
    // — a declared payload field, an undeclared extra field of a known kind, an
    // unknown kind, a missing discriminator — is reduced before the stringify,
    // never carried through it. One denial row per shape.
    const stringifySpy = vi.spyOn(JSON, 'stringify');
    try {
      const huge = 'x'.repeat(4 * 1024 * 1024);

      const extraField: LlmEvent = { type: 'tool_result', id: 't1', output: 'ok', isError: false } as LlmEvent;
      (extraField as unknown as Record<string, unknown>).extra = huge;

      const extraToolCall: LlmEvent = { type: 'tool_call', id: 't1', name: 'Bash', input: { command: 'ls' }, messageId: null };
      (extraToolCall as unknown as Record<string, unknown>).extra = huge;

      const unknown = { type: 'future_kind', input: huge } as unknown as LlmEvent;
      const typeless = { input: huge } as unknown as LlmEvent;
      const declared: LlmEvent = { type: 'tool_result', id: 't1', output: { stdout: huge }, isError: false };

      for (const event of [extraField, extraToolCall, unknown, typeless, declared]) {
        const { event: clipped, size } = clipLlmEvent(event);
        expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
        expect(JSON.stringify(clipped).length).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
        expect(JSON.stringify(clipped)).not.toContain(huge);
      }

      // The bounded-work property: the oversized string must never reach
      // JSON.stringify as part of an unclipped event — the pre-pass reduces
      // before the only whole-event serialization runs.
      const reachedHuge = stringifySpy.mock.calls.some(([arg]) => holdsStringLongerThan(arg, MAX_LLM_EVENT_CHARS));
      expect(reachedHuge).toBe(false);
    } finally {
      stringifySpy.mockRestore();
    }
  });

  it('CWE-400 keeps an unknown discriminator within the cap when only an unknown field is oversized', () => {
    // A known kind whose undeclared extra field is oversized, and an unknown kind
    // that fits the cap: both must return a size the buffer budget can trust.
    const huge = 'x'.repeat(MAX_LLM_EVENT_CHARS + 1);
    const extraField: LlmEvent = { type: 'tool_result', id: 't1', output: 'ok', isError: false } as LlmEvent;
    (extraField as unknown as Record<string, unknown>).extra = huge;
    const { event: clippedExtra, size: sizeExtra } = clipLlmEvent(extraField);
    expect(JSON.stringify(clippedExtra).length).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
    expect(sizeExtra).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);

    const unknownSmall = { type: 'future_kind', input: 'ok' } as unknown as LlmEvent;
    const { event: clippedUnknown, size: sizeUnknown } = clipLlmEvent(unknownSmall);
    expect(JSON.stringify(clippedUnknown).length).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
    expect(sizeUnknown).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
  });

  it('CWE-20 survives a malformed frame whose scalars or status are not the declared shape', () => {
    // The frame's event is only cast to LlmEvent on arrival, so a malformed or
    // newer frame can carry a non-string id/name/model or a status that is not
    // the declared union. The size walk must gate every read on its shape and
    // degrade to the transient-error fallback instead of throwing, so one bad
    // frame cannot abort onEvent and stop the stream.
    const cases: unknown[] = [
      { type: 'tool_result', id: ['x'], output: 'ok', isError: false },
      { type: 'tool_call', id: 't1', name: ['x'], input: {}, messageId: {} },
      { type: 'assistant_message', messageId: 7, model: null, text: 'hi', subagent: false },
      { type: 'session_start', sessionId: ['s'], model: {}, tools: [] },
      { type: 'turn_end', status: null },
      { type: 'turn_end', status: 'failed' },
      { type: 'turn_end', status: { type: 'failed' } },
      { type: 'turn_end', status: { type: 'failed', reason: ['r'] } },
      {},
    ];
    for (const event of cases) {
      const { event: clipped, size } = clipLlmEvent(event as LlmEvent);
      expect(size).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
      expect(JSON.stringify(clipped).length).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
    }
    // The stream keeps delivering after a malformed frame: the hook's onEvent
    // must not throw, or the SSE handler aborts and the view stops updating.
    const { result } = renderHook(() => useLlmEventStream(7, 0, true));
    const { onEvent } = capturedHandlers();
    expect(() => onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: cases[0] }, '0:0:1:0')).not.toThrow();
    expect(() => onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: cases[4] }, '0:0:1:1')).not.toThrow();
    expect(result.current.events).toHaveLength(0); // not yet flushed to state
  });

  it('CWE-20 degrades a frame carrying a null or absent event instead of aborting onEvent', async () => {
    // CWE-20: `event: null` is valid JSON and an `llm.event`
    // frame can omit the field entirely. Reading the discriminator on such an
    // event would throw inside onEvent; the SSE retry then replays the same
    // frame from the start forever and the Live view dies. Both shapes must
    // land on the transient-error note and let the stream continue.
    const { result } = renderHook(() => useLlmEventStream(7, 0, true));
    const { onEvent } = capturedHandlers();

    await act(async () => {
      expect(() => onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: null }, '0:0:1:0')).not.toThrow();
      expect(() => onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0 }, '0:0:1:1')).not.toThrow();
      await flushFrame();
    });

    expect(result.current.events).toHaveLength(2);
    for (const event of result.current.events) {
      expect(event.type).toBe('transient_error');
      expect((event as { message: string }).message).toContain('malformed event');
    }

    // The stream still delivers well-formed frames afterwards.
    const good: LlmEvent = { type: 'transient_error', message: 'after' };
    await act(async () => {
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: good }, '0:0:1:2');
      await flushFrame();
    });
    expect(result.current.events.map((e) => (e as { message?: string }).message)).toEqual([
      '[malformed event]',
      '[malformed event]',
      'after',
    ]);
  });

  it('denial_of_service CWE-20 degrades a turn_end whose status or usage is not the declared shape', async () => {
    // The frame's event is only cast to LlmEvent, so a hostile or buggy backend
    // can send a small `turn_end` whose `status` is null (or whose `usage` is
    // missing). The summary card dereferences `status.type` and `usage.input`
    // unguarded; buffering such an event would throw in render and white-screen
    // the page subtree. A shape-invalid turn_end must degrade to the
    // transient-error note so every buffered event is render-safe.
    const { result } = renderHook(() => useLlmEventStream(7, 0, true));
    const { onEvent } = capturedHandlers();

    const badStatus: LlmEvent = { type: 'turn_end', status: null } as unknown as LlmEvent;
    const badUsage: LlmEvent = {
      type: 'turn_end',
      status: { type: 'success' },
      usage: null,
    } as unknown as LlmEvent;

    await act(async () => {
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: badStatus }, '0:0:30:0');
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: badUsage }, '0:0:30:1');
      // A well-formed turn_end still buffers whole — the guard is shape-only.
      const good: LlmEvent = {
        type: 'turn_end',
        status: { type: 'success' },
        usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, reasoning: 0 },
        costUsd: null, model: null, durationMs: null, durationApiMs: null, numTurns: 1, resultText: 'ok',
      };
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: good }, '0:0:30:2');
      // An assistant_message whose text is not a string throws in the view too
      // (`text.trim()`); the same guard covers it.
      const badText: LlmEvent = { type: 'assistant_message', messageId: 'm1', model: null, text: null, subagent: false } as unknown as LlmEvent;
      onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: badText }, '0:0:30:3');
      await flushFrame();
    });

    expect(result.current.events.map((e) => (e as { message?: string }).message)).toEqual([
      '[malformed event]',
      '[malformed event]',
      undefined, // the good turn_end, buffered whole
      '[malformed event]',
    ]);
    expect(result.current.events[2].type).toBe('turn_end');
  });

  // ~700k frames to overflow the 16 MiB budget: well under a second locally, over 5 s on a
  // loaded CI runner, hence the explicit timeout.
  it('denial_of_service CWE-400 evicts malformed frames past MAX_LLM_EVENTS and past MAX_LLM_BUFFER_CHARS', async () => {
    // The malformed-event note is appended to the same buffer as any clipped
    // event, so the eviction caps must run on that path too — a hostile or
    // buggy backend streaming `event: null` (or omitting `event`) forever would
    // otherwise grow the buffer without limit.
    const { result } = renderHook(() => useLlmEventStream(7, 0, true));
    const { onEvent } = capturedHandlers();

    await act(async () => {
      // `event: null` frames past MAX_LLM_EVENTS, with well-formed frames mixed
      // in — both arms share one buffer and one eviction loop.
      for (let i = 0; i <= MAX_LLM_EVENTS; i++) {
        onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event: null }, `0:0:${i}:0`);
        if (i % 50 === 0) {
          const event: LlmEvent = { type: 'transient_error', message: `good${i}` };
          onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event }, `0:0:${i}:1`);
        }
      }
      await flushFrame();
    });
    expect(result.current.events).toHaveLength(MAX_LLM_EVENTS);
    expect(result.current.truncated).toBe(true);
    for (const event of result.current.events) {
      expect(JSON.stringify(event).length).toBeLessThanOrEqual(MAX_LLM_EVENT_CHARS);
    }

    // Absent-`event` frames past MAX_LLM_BUFFER_CHARS. The frames must land past
    // the replay cursor arm 1 left behind (`lastLineOffset = MAX_LLM_EVENTS`) or
    // the guard drops them before pushEvent, and the ~50-char notes they cost are
    // nowhere near a 16 MiB budget on their own — so fill the buffer with large
    // well-formed events first and let the malformed flood push it over. The
    // assertion is eviction (the buffer shrank and the first good events are
    // gone), not the cap inequality, which would hold vacuously on an empty
    // buffer.
    const fillText = 'y'.repeat(MAX_LLM_EVENT_CHARS - 200);
    const fillCount = Math.floor((MAX_LLM_BUFFER_CHARS / MAX_LLM_EVENT_CHARS) * 0.75);
    const malformedCount = Math.ceil((2 * MAX_LLM_BUFFER_CHARS) / 50);
    await act(async () => {
      for (let i = 0; i < fillCount; i++) {
        const event: LlmEvent = { type: 'assistant_message', messageId: `fill${i}`, model: null, text: fillText, subagent: false };
        onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event }, `0:0:${MAX_LLM_EVENTS + 1 + i}:0`);
      }
      for (let i = 0; i < malformedCount; i++) {
        onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0 }, `0:0:${MAX_LLM_EVENTS + 1 + fillCount + i}:1`);
      }
      await flushFrame();
    });
    const held = result.current.events;
    expect(held.length).toBeLessThan(fillCount + malformedCount);
    expect(result.current.truncated).toBe(true);
    // The eviction reached back into the large well-formed fill: those are gone.
    expect(held.some((e) => (e as { messageId?: string }).messageId?.startsWith('fill'))).toBe(false);
    expect(held.reduce((n, e) => n + JSON.stringify(e).length, 0)).toBeLessThanOrEqual(MAX_LLM_BUFFER_CHARS);
    // The tail is the malformed flood's notes, so the arm really ran this path.
    expect((held[held.length - 1] as { message?: string }).message).toContain('malformed event');
  }, 30_000);

  it('bf4839ea CWE-400 evicts the oldest events once the buffer passes its total size budget', async () => {
    const { result } = renderHook(() => useLlmEventStream(7, 0, true));
    const { onEvent } = capturedHandlers();
    const text = 'y'.repeat(MAX_LLM_EVENT_CHARS - 200);
    const count = Math.ceil(MAX_LLM_BUFFER_CHARS / MAX_LLM_EVENT_CHARS) + 10;

    await act(async () => {
      for (let i = 0; i < count; i++) {
        const event: LlmEvent = { type: 'assistant_message', messageId: `m${i}`, model: null, text, subagent: false };
        onEvent('llm.event', { processId: 7, cmdIndex: 0, attemptIndex: 0, event }, `0:0:${i}:0`);
      }
      await flushFrame();
    });

    const held = result.current.events;
    expect(held.length).toBeLessThan(count);
    expect(held.reduce((n, e) => n + JSON.stringify(e).length, 0)).toBeLessThanOrEqual(MAX_LLM_BUFFER_CHARS);
    expect((held[held.length - 1] as { messageId: string }).messageId).toBe(`m${count - 1}`);
    expect(result.current.truncated).toBe(true);
  });

  it('resets the buffer and stops the stream when disabled', () => {
    const { rerender } = renderHook(({ enabled }) => useLlmEventStream(7, 0, enabled), {
      initialProps: { enabled: true },
    });
    expect(openStreamMock).toHaveBeenCalledTimes(1);

    rerender({ enabled: false });
    expect(closeMock).toHaveBeenCalled();
  });
});

describe('boundedStringSize', () => {
  // Mostly plain units, so the native fast path is taken often, plus every escape class.
  const unit = fc.oneof(
    { weight: 8, arbitrary: fc.constantFrom('a', 'Z', '0', ' ', '~', 'é', '\u2028') },
    { weight: 1, arbitrary: fc.constantFrom('"', '\\', '\n', '\u0001', '\ud83d\ude00', '\ud800', '\udfff') },
  );
  const text = fc.array(unit, { maxLength: 40 }).map((units) => units.join(''));

  it('matches the per-unit walk for every string and cap', () => {
    fc.assert(
      fc.property(text, fc.integer({ min: 0, max: 50 }), (s, cap) => {
        expect(boundedStringSize(s, cap)).toBe(walkedStringSize(s, cap));
      }),
    );
  });

  it('is the serialized length of a string under the cap', () => {
    fc.assert(
      fc.property(text, (s) => {
        expect(boundedStringSize(s, 1000)).toBe(JSON.stringify(s).length);
      }),
    );
  });

  it('sizes a plain string past the cap at cap + 1, like the walk', () => {
    expect(boundedStringSize('y'.repeat(100), 10)).toBe(11);
    expect(boundedStringSize('y'.repeat(8), 10)).toBe(10);
    expect(boundedStringSize('y'.repeat(9), 10)).toBe(11);
  });
});
