'use client';

import { useEffect, useState } from 'react';
import { useQueryClient, type QueryClient } from '@tanstack/react-query';
import { openStream } from './sse';
import { queryKeys } from './queries';
import { useActiveInstanceId } from '@/lib/instances/use-instances';
import type { LlmEvent, LlmStreamFrame, LlmTurnStatus, LlmUsage, ProcessListResponse } from '@/types/homer';

// Only the flow summaries the events are about: a root's, or one already listing the run.
// A child new to its flow shows up on the summary's own poll or its parent's next event.
function invalidateFlowSummariesFor(queryClient: QueryClient, processIds: ReadonlySet<number>) {
  queryClient.invalidateQueries({
    queryKey: queryKeys.allFlowSummaries(),
    predicate: (query) =>
      processIds.has(query.queryKey[1] as number) ||
      ((query.state.data as ProcessListResponse | undefined)?.processes ?? []).some((p) => processIds.has(p.id)),
  });
}

const PROCESS_EVENTS: ReadonlySet<string> = new Set(['process.status', 'process.cmd']);
const QUESTION_EVENTS: ReadonlySet<string> = new Set(['question.asked', 'question.answered', 'question.expired']);
const MESSAGE_EVENTS: ReadonlySet<string> = new Set(['message.queued', 'message.delivered', 'message.undelivered']);

export function useProcessStatusStream() {
  const queryClient = useQueryClient();
  // Re-subscribe when the active instance changes so the stream reconnects to
  // the new backend rather than tailing the old one.
  const activeInstanceId = useActiveInstanceId();

  useEffect(() => {
    // Process events are coalesced like useLlmEventStream's frames — a burst
    // within one animation frame costs one invalidation per query, flushed after the
    // burst so the last event's state is always refetched.
    const pendingIds = new Set<number>();
    let rafId: number | null = null;
    let cancelled = false;

    const flush = () => {
      rafId = null;
      if (cancelled) return;
      const ids = new Set(pendingIds);
      pendingIds.clear();
      queryClient.invalidateQueries({ queryKey: queryKeys.allProcesses() });
      if (ids.size === 0) return;
      for (const processId of ids) {
        queryClient.invalidateQueries({ queryKey: queryKeys.process(processId) });
        // The artifact REST snapshot carries X-File-Complete (isComplete);
        // nothing else refreshes it, so a finished process would leave an
        // open output dialog stuck in "streaming" mode (persistent Live
        // badge). Invalidate the process's artifact snapshots so they
        // refetch with the now-complete flag.
        queryClient.invalidateQueries({
          queryKey: queryKeys.artifactContentByProcess(processId),
        });
      }
      invalidateFlowSummariesFor(queryClient, ids);
    };

    const close = openStream('/api/v1/stream/processes', {
      onEvent(name, data) {
        if (PROCESS_EVENTS.has(name)) {
          const processId = (data as { processId?: number })?.processId;
          if (typeof processId === 'number') pendingIds.add(processId);
          if (rafId === null) rafId = requestAnimationFrame(flush);
        } else if (MESSAGE_EVENTS.has(name)) {
          const processId = (data as { processId?: number })?.processId;
          if (typeof processId === 'number') {
            queryClient.invalidateQueries({ queryKey: queryKeys.runMessages(processId) });
          }
        } else if (QUESTION_EVENTS.has(name)) {
          const processId = (data as { processId?: number })?.processId;
          queryClient.invalidateQueries({ queryKey: queryKeys.allQuestions() });
          // openQuestions counts ride the process list / detail responses.
          queryClient.invalidateQueries({ queryKey: queryKeys.allProcesses() });
          if (typeof processId === 'number') {
            queryClient.invalidateQueries({ queryKey: queryKeys.process(processId) });
            invalidateFlowSummariesFor(queryClient, new Set([processId]));
          }
        }
      },
    });
    return () => {
      cancelled = true;
      if (rafId !== null) cancelAnimationFrame(rafId);
      close();
    };
  }, [queryClient, activeInstanceId]);
}

export function useAgentReloadStream() {
  const queryClient = useQueryClient();
  const activeInstanceId = useActiveInstanceId();

  useEffect(() => {
    const close = openStream('/api/v1/stream/agents', {
      onEvent(name) {
        if (name === 'agent.reload') {
          queryClient.invalidateQueries({ queryKey: queryKeys.agents() });
        }
      },
    });
    return close;
  }, [queryClient, activeInstanceId]);
}

export interface LogStreamState {
  content: string;
  /** Byte offset of the end of `content` in the source log file. */
  offset: number;
  ended: boolean;
}

const utf8 = typeof TextEncoder !== 'undefined' ? new TextEncoder() : null;

function byteLength(s: string): number {
  return utf8 ? utf8.encode(s).length : s.length;
}

export function useProcessLogStream(
  processId: number,
  cmd: number,
  stream: 'out' | 'err',
  enabled: boolean
): LogStreamState {
  const [state, setState] = useState<LogStreamState>({ content: '', offset: 0, ended: false });

  useEffect(() => {
    if (!enabled) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- reset buffer on stream (re)subscribe
    setState({ content: '', offset: 0, ended: false });

    const path = `/api/v1/stream/processes/${processId}/logs?cmd=${cmd}&stream=${stream}`;
    const close = openStream(path, {
      onEvent(name, data) {
        if (name === 'log.chunk') {
          // Backend emits `{ offset, content }` where offset is the END byte
          // position of this chunk and content is the bytes since the
          // previous chunk's offset (or 0 on first chunk / reconnect).
          // We can't rely on appending blindly: openStream auto-reconnects
          // on transient errors and the backend restarts at byte 0 unless
          // we pass resumeFromOffset (which we don't), so the post-reconnect
          // first chunk would re-deliver content we already have.
          const d = data as { offset?: number; content?: string };
          const incomingOffset = typeof d.offset === 'number' ? d.offset : -1;
          const incomingContent = d.content ?? '';
          setState((s) => {
            if (incomingOffset < 0) {
              // Old/unexpected payload shape — best-effort append.
              return {
                content: s.content + incomingContent,
                offset: s.offset + byteLength(incomingContent),
                ended: s.ended,
              };
            }
            const incomingByteLen = byteLength(incomingContent);
            const expectedPrev = incomingOffset - incomingByteLen;
            if (expectedPrev === s.offset) {
              // Strict continuation of our current buffer — append.
              return {
                content: s.content + incomingContent,
                offset: incomingOffset,
                ended: s.ended,
              };
            }
            if (incomingOffset <= s.offset) {
              // Reconnect re-delivered bytes we already have — drop.
              return s;
            }
            // Gap (we lost bytes somehow) or restart from a different
            // baseline — replace the buffer so the dialog shows what the
            // backend currently has rather than splice corruption.
            return {
              content: incomingContent,
              offset: incomingOffset,
              ended: s.ended,
            };
          });
        } else if (name === 'log.end') {
          setState((s) => ({ ...s, ended: true }));
        }
      },
    });
    return close;
  }, [processId, cmd, stream, enabled]);

  return state;
}

export interface LlmEventStreamState {
  events: LlmEvent[];
  ended: boolean;
  /** True once the buffer has dropped events past MAX_LLM_EVENTS. */
  truncated: boolean;
}

// CWE-400: an unbounded event buffer (and a `[...events, frame.event]` copy on
// every frame) lets a flooding or endless harness stream exhaust the tab's
// memory. Cap the buffer and drop the oldest entries past this size; the cap
// is a constant, so the drop is O(1) amortized relative to the number of
// events received.
export const MAX_LLM_EVENTS = 5000;

// CWE-400 (review thread bf4839ea): one event can carry a multi-MiB tool input or
// output, so the count cap alone does not bound memory. Bound each event and the
// buffer's total size here, not only through the server's output cap.
export const MAX_LLM_EVENT_CHARS = 64 * 1024;
export const MAX_LLM_BUFFER_CHARS = 16 * 1024 * 1024;

const NOTE_SUFFIX = " characters, too large to show here; the command's raw output artifact has it in full]";

// Nesting cap for the pre-pass walk. Exceeding it reports an oversized value
// rather than aborting the walk silently, so a deeply nested payload is clipped
// instead of slipping past the bound (and the recursion cannot overflow).
const MAX_LLM_SIZE_DEPTH = 256;

// A UTF-16 unit JSON.stringify writes as more than itself: a quote, a backslash,
// a control character or a surrogate (paired ones take the per-unit walk too).
const ESCAPED_UNIT = /["\\\u0000-\u001f\ud800-\udfff]/;

/**
 * Serialized length of a JSON string, counted without allocating the serialized
 * form. `s.length` alone would miss the `\uXXXX` inflation of control characters
 * and lone surrogates, under-counting exactly the payloads the cap exists for.
 * Costs O(cap), not O(s). A string whose first `cap - 1` units need no escape is
 * sized by one native regex test instead of the per-unit walk, with the same
 * result: the walk would add 1 per unit and pass the cap at exactly `cap + 1`.
 */
export function boundedStringSize(s: string, cap: number): number {
  if (cap < 2 || ESCAPED_UNIT.test(s.length < cap ? s : s.slice(0, cap - 1))) return walkedStringSize(s, cap);
  return s.length <= cap - 2 ? s.length + 2 : cap + 1;
}

/** {@link boundedStringSize} unit by unit, stopping once `cap` is passed. */
export function walkedStringSize(s: string, cap: number): number {
  let size = 2; // opening and closing quote
  for (let i = 0; i < s.length; i++) {
    if (isSurrogatePairAt(s, i)) { size += 2; i++; continue; } // surrogate pair
    size += escapedUnitSize(s.charCodeAt(i));
    if (size > cap) return size;
  }
  return size;
}

/** Whether `s` holds a high surrogate at `i` followed by a low one. */
function isSurrogatePairAt(s: string, i: number): boolean {
  const unit = s.charCodeAt(i);
  if (unit < 0xd800 || unit > 0xdbff || i + 1 >= s.length) return false;
  const next = s.charCodeAt(i + 1);
  return next >= 0xdc00 && next <= 0xdfff;
}

// Control characters JSON escapes in two characters (\b \t \n \f \r); the rest take six (\uXXXX).
const SHORT_ESCAPES = new Set([0x08, 0x09, 0x0a, 0x0c, 0x0d]);

/** Serialized length of one UTF-16 unit outside a surrogate pair. */
function escapedUnitSize(unit: number): number {
  if (unit === 0x22 || unit === 0x5c) return 2; // \" and \\
  if (unit < 0x20) return SHORT_ESCAPES.has(unit) ? 2 : 6;
  if (unit >= 0xd800 && unit <= 0xdfff) return 6; // lone surrogate escapes as \uXXXX
  return 1; // printable ASCII, DEL and non-ASCII serialize as themselves
}

/**
 * Bounds the pre-serialization cost of measuring a payload value. A plain
 * `JSON.stringify(value).length` would itself copy a multi-MiB value, which is
 * the cost this pre-pass exists to avoid, so the value is walked in place and
 * only until the cap is passed. Every JSON-significant character is counted
 * (string escapes, keys, separators, container punctuation), so the result never
 * falls below the serialized size and a payload of empty containers or nulls
 * cannot slip past the cap. Returns a length that may overshoot the cap, which
 * is all the oversize check needs.
 */
function boundedSize(value: unknown, cap: number, depth = 0): number {
  if (depth > MAX_LLM_SIZE_DEPTH) return Number.MAX_SAFE_INTEGER;
  if (typeof value === 'string') return boundedStringSize(value, cap);
  if (Array.isArray(value)) return boundedArraySize(value, cap, depth);
  if (value !== null && typeof value === 'object') return boundedObjectSize(value, cap, depth);
  return primitiveSize(value);
}

/** Serialized size of a non-string primitive, a function or a symbol. */
function primitiveSize(value: unknown): number {
  if (typeof value === 'number') return Number.isFinite(value) ? String(value).length : 4;
  if (typeof value === 'boolean') return value ? 4 : 5;
  return 4; // null, undefined, a function or symbol serialize as null, or are skipped
}

function boundedArraySize(value: unknown[], cap: number, depth: number): number {
  let size = 2; // brackets
  for (const item of value) {
    size += boundedSize(item, cap, depth + 1) + 1; // comma
    if (size > cap) return size;
  }
  return size;
}

function boundedObjectSize(value: object, cap: number, depth: number): number {
  // Walked in place — `Object.entries` would materialize every key before the
  // cap could abort, the cost this walk exists to bound.
  let size = 2; // braces
  for (const key in value) {
    if (!Object.prototype.hasOwnProperty.call(value, key)) continue;
    size += boundedStringSize(key, cap) + 1 // quotes and colon
      + boundedSize((value as Record<string, unknown>)[key], cap, depth + 1) + 1; // comma
    if (size > cap) return size;
  }
  return size;
}

/** The field of each event kind the pre-pass can replace with the note. */
function clippableField(event: LlmEvent): unknown {
  switch (event.type) {
    case 'tool_call': return event.input;
    case 'tool_result': return event.output;
    case 'assistant_message': return event.text;
    case 'turn_end': return event.resultText;
    case 'session_start': return event.tools;
    case 'transient_error': return event.message;
    // CWE-400: the frame's event is only cast to LlmEvent on
    // arrival, so a newer server or a malformed frame can carry a discriminator
    // no arm matches. Offer the whole event to the pre-pass: an oversized
    // unknown event is reduced below to the transient-error note, never
    // serialized whole; one that fits is kept as-is.
    default: return event;
  }
}

/**
 * CWE-400: the pre-pass sees the whole event, not only its
 * payload field — a known kind can also carry an undeclared extra field
 * (`{type:'tool_result', output:'ok', extra:<4 MiB>}`) the walk above is blind
 * to, so `clipLlmEvent` would copy it in the whole-event stringify below just
 * to discard it. Offering the whole envelope keeps that cost on the same
 * bounded path as an unknown kind.
 */
function wholeEventSize(event: LlmEvent, cap: number): number {
  return boundedSize(event, cap);
}

function replaceClippableField(event: LlmEvent, note: string): LlmEvent {
  switch (event.type) {
    case 'tool_call': return { ...event, input: note };
    case 'tool_result': return { ...event, output: note };
    case 'assistant_message': return { ...event, text: note };
    case 'turn_end': return { ...event, resultText: note };
    case 'session_start': return { ...event, tools: [] };
    case 'transient_error': return { ...event, message: note };
    // CWE-400: an unknown or absent discriminator has no
    // payload field to reduce — degrade the whole event to the transient-error
    // note, mirroring the bounded transient_error case.
    default: return { type: 'transient_error', message: note };
  }
}

/**
 * Length of the first identity scalar past the cap — an id, name, messageId,
 * model, sessionId or a failed or stopped turn's status reason — or null if none
 * is. Unlike the payload field these cannot be reduced to the note, so an
 * oversized one must take the transient-error fallback before the event is
 * serialized whole.
 */
function oversizedScalarSize(event: LlmEvent): number | null {
  for (const scalar of identityScalars(event)) {
    // CWE-20: a malformed frame can carry a non-string scalar
    // (an array id, a null model) the cast hid — gate the walk on `typeof`.
    if (typeof scalar !== 'string') continue;
    const size = boundedStringSize(scalar, MAX_LLM_EVENT_CHARS);
    if (size > MAX_LLM_EVENT_CHARS) return size;
  }
  return null;
}

/** The identity scalars of each event kind, in the order they are checked. */
function identityScalars(event: LlmEvent): (string | null)[] {
  switch (event.type) {
    case 'tool_call': return [event.id, event.name, event.messageId];
    case 'tool_result': return [event.id];
    case 'assistant_message': return [event.messageId, event.model];
    case 'session_start': return [event.sessionId, event.model];
    case 'turn_end': return [event.model, stopReason(event.status)];
    case 'transient_error': return []; // the message is the payload field, not a scalar
    // CWE-400: an unknown or absent discriminator has no
    // known identity scalars to check; the pre-pass handles the whole event.
    default: return [];
  }
}

/** A failed or stopped turn's reason, or null for any other status. */
function stopReason(status: LlmTurnStatus | null | undefined): string | null {
  // CWE-20: the frame's event is only cast to LlmEvent on
  // arrival, so `status` can be missing, null or shaped wrong on a
  // malformed or newer frame — reading `.type` on it must not throw.
  return typeof status?.type === 'string'
    && (status.type === 'failed' || status.type === 'stopped')
    && typeof status.reason === 'string'
    ? status.reason : null;
}

function transientNote(note: string): { event: LlmEvent; size: number } {
  const fallback: LlmEvent = { type: 'transient_error', message: note };
  return { event: fallback, size: JSON.stringify(fallback).length };
}

/**
 * CWE-20: the frame's `event` is only cast to LlmEvent on
 * arrival, so a malformed frame can carry `event: null` or omit it — reading
 * the discriminator in the clip pipeline would throw, aborting onEvent, and
 * the SSE retry would replay the same frame forever. Such an event has no
 * payload to bound, so it degrades to the transient-error note.
 */
function shapedEvent(event: LlmEvent | null | undefined): LlmEvent | null {
  return event !== null && typeof event === 'object' ? event : null;
}

const LLM_USAGE_FIELDS = ['input', 'output', 'cacheRead', 'cacheWrite', 'reasoning'] as const;

function isLlmUsage(value: unknown): value is LlmUsage {
  if (value === null || typeof value !== 'object') return false;
  return LLM_USAGE_FIELDS.every((field) => {
    const v = (value as Record<string, unknown>)[field];
    return typeof v === 'number' && Number.isFinite(v);
  });
}

function isLlmTurnStatus(value: unknown): value is LlmTurnStatus {
  if (value === null || typeof value !== 'object') return false;
  const type = (value as Record<string, unknown>).type;
  if (type === 'success') return true;
  if (type === 'failed' || type === 'stopped') {
    return typeof (value as Record<string, unknown>).reason === 'string';
  }
  return false;
}

/**
 * CWE-20: the frame's `event` is only cast to LlmEvent
 * on arrival, so a hostile or buggy backend can send an event whose fields are
 * not the declared shape — and the conversation view dereferences some of them
 * unconditionally (`turn_end`: `status.type`, `usage.input`; `assistant_message`:
 * `text.trim()`), where a throw white-screens the whole subtree: no error
 * boundary wraps it. Every field listed here is one the renderer reads without
 * guarding; an event failing any of them degrades to the transient-error note
 * so every buffered event is render-safe.
 */
function isRenderSafe(event: LlmEvent): boolean {
  switch (event.type) {
    case 'turn_end': return isLlmTurnStatus(event.status) && isLlmUsage(event.usage);
    case 'assistant_message': return typeof event.text === 'string';
    default: return true;
  }
}

/**
 * Replaces an oversized event's payload field with a note, so a held event never
 * exceeds MAX_LLM_EVENT_CHARS of serialized size. An event still oversized after
 * that — an oversized identity scalar that cannot be reduced, or a reduced event
 * still past the cap — becomes a transient-error note. Returns the event and its
 * size.
 *
 * CWE-400: the pre-pass walks the whole event in place before
 * any whole-event JSON.stringify, so a multi-MiB payload — in the payload field,
 * an undeclared extra field or an unknown kind — is never copied just to be
 * discarded. It is not a guarantee that no serialization remains: an event still
 * oversized after reduction — an identity scalar, or the event's JSON overhead —
 * is serialized once more and takes the transient-error fallback below.
 */
export function clipLlmEvent(event: LlmEvent): { event: LlmEvent; size: number } {
  // Pre-pass: measure the whole event without serializing it
  // (CWE-400), so a multi-MiB payload — in the payload field, in an undeclared
  // extra field of a known kind, or in an unknown kind — is never copied just
  // to be discarded. Then reduce the payload field when it alone is at or past
  // the cap.
  const wholeSize = wholeEventSize(event, MAX_LLM_EVENT_CHARS);
  const fieldSize = boundedSize(clippableField(event), MAX_LLM_EVENT_CHARS);
  let note: string | null = null;
  let clipped: LlmEvent = event;
  if (fieldSize >= MAX_LLM_EVENT_CHARS) {
    note = `[${fieldSize}${NOTE_SUFFIX}`;
    clipped = replaceClippableField(event, note);
  }

  const scalarSize = oversizedScalarSize(event);
  if (scalarSize !== null) return transientNote(`[${scalarSize}${NOTE_SUFFIX}`);

  if (wholeSize >= MAX_LLM_EVENT_CHARS) {
    // CWE-400: the size hides outside the declared payload
    // field — an undeclared extra property of a known kind. Reducing the field
    // alone cannot shed it (the reduction spreads the event, so unknown
    // properties survive), so re-measure the reduced event and degrade to the
    // transient-error note when it still carries the size — never serializing
    // the oversized original.
    if (note === null) {
      note = `[${wholeSize}${NOTE_SUFFIX}`;
      clipped = replaceClippableField(event, note);
    }
    if (boundedSize(clipped, MAX_LLM_EVENT_CHARS) >= MAX_LLM_EVENT_CHARS) {
      return transientNote(note);
    }
  }

  return serializedClip(event, clipped, note);
}

/**
 * Serializes the reduced event once and keeps it when it fits, else returns the
 * transient-error note. `note` is the one the pre-pass applied, or null.
 */
function serializedClip(
  event: LlmEvent,
  clipped: LlmEvent,
  note: string | null,
): { event: LlmEvent; size: number } {
  let size = JSON.stringify(clipped).length;
  if (size > MAX_LLM_EVENT_CHARS && note === null) {
    // Defensive: the walk reported the event under the cap but its serialized
    // form is past it. Apply the note exactly as the pre-pass would have, so a
    // boundary-sized payload keeps its typed event.
    note = `[${size}${NOTE_SUFFIX}`;
    clipped = replaceClippableField(event, note);
    size = JSON.stringify(clipped).length;
  }
  if (size <= MAX_LLM_EVENT_CHARS) return { event: clipped, size };
  return transientNote(note ?? `[${size}${NOTE_SUFFIX}`);
}

/** The attempt and `(lineOffset, eventIndex)` of the last accepted frame. */
interface FrameCursor {
  attempt: number;
  lineOffset: number;
  eventIndex: number;
}

/** A frame id's `cmd:attempt:lineOffset:eventIndex` parts, or null when it does not parse. */
function parseFrameId(id: string | undefined): FrameCursor | null {
  const parts = (id ?? '').split(':');
  if (parts.length !== 4) return null;
  const attempt = Number(parts[1]);
  const lineOffset = Number(parts[2]);
  const eventIndex = Number(parts[3]);
  if (!Number.isFinite(attempt) || !Number.isFinite(lineOffset) || !Number.isFinite(eventIndex)) return null;
  return { attempt, lineOffset, eventIndex };
}

/**
 * Moves the dedupe cursor past a frame's `cmd:attempt:lineOffset:eventIndex` id.
 * Returns null for a replayed frame to drop, otherwise whether the frame starts a
 * newer attempt (the buffer resets). An id that does not parse leaves the cursor
 * alone and keeps the frame.
 */
function advanceCursor(cursor: FrameCursor, id: string | undefined): boolean | null {
  const frame = parseFrameId(id);
  if (frame === null) return false;
  let resetBuffer = false;
  if (frame.attempt !== cursor.attempt) {
    cursor.attempt = frame.attempt;
    cursor.lineOffset = -1;
    cursor.eventIndex = -1;
    resetBuffer = true;
  }
  const isReplay =
    frame.lineOffset < cursor.lineOffset ||
    (frame.lineOffset === cursor.lineOffset && frame.eventIndex <= cursor.eventIndex);
  if (!resetBuffer && isReplay) return null;
  cursor.lineOffset = frame.lineOffset;
  cursor.eventIndex = frame.eventIndex;
  return resetBuffer;
}

/**
 * The buffered form of one `llm.event` frame's event: clipped by clipLlmEvent,
 * or the transient-error note when the event is absent or not render-safe.
 */
function frameEvent(frame: LlmStreamFrame | null | undefined): { event: LlmEvent; size: number } {
  // CWE-20: a null or absent event degrades to the
  // transient-error note instead of aborting onEvent (see shapedEvent).
  const shaped = shapedEvent(frame?.event);
  if (shaped === null) return transientNote('[malformed event]');
  // CWE-20: an event the renderer cannot safely
  // dereference degrades to the transient-error note (see isRenderSafe).
  const renderSafe = isRenderSafe(shaped) ? shaped : transientNote('[malformed event]').event;
  return clipLlmEvent(renderSafe);
}

interface LlmEventBuffer {
  /** Appends an event, evicting the oldest past MAX_LLM_EVENTS / MAX_LLM_BUFFER_CHARS. */
  push(event: LlmEvent, size: number): void;
  /** Empties the buffer for a newer attempt; the next push publishes it. */
  reset(): void;
  /** Marks the stream ended. */
  end(): void;
  /** Stops publishing and cancels a pending flush. */
  dispose(): void;
}

/** Mutable event buffer published through `publish` at most once per animation frame. */
function createLlmEventBuffer(publish: (state: LlmEventStreamState) => void): LlmEventBuffer {
  const buffer: LlmEvent[] = [];
  const sizes: number[] = [];
  let totalChars = 0;
  let truncated = false;
  let ended = false;
  let rafId: number | null = null;
  let cancelled = false;

  const flush = () => {
    rafId = null;
    if (cancelled) return;
    publish({ events: buffer.slice(), ended, truncated });
  };
  const scheduleFlush = () => {
    if (rafId === null) rafId = requestAnimationFrame(flush);
  };

  return {
    // CWE-400: both the malformed-event note and a
    // clipped event land in this buffer, so the eviction caps must run for both
    // arms — a malformed frame that skipped them would grow it without limit.
    push(event, size) {
      buffer.push(event);
      sizes.push(size);
      totalChars += size;
      while (buffer.length > MAX_LLM_EVENTS || totalChars > MAX_LLM_BUFFER_CHARS) {
        buffer.shift();
        totalChars -= sizes.shift() ?? 0;
        truncated = true;
      }
      scheduleFlush();
    },
    reset() {
      buffer.length = 0;
      sizes.length = 0;
      totalChars = 0;
      truncated = false;
    },
    end() {
      ended = true;
      scheduleFlush();
    },
    dispose() {
      cancelled = true;
      if (rafId !== null) cancelAnimationFrame(rafId);
    },
  };
}

/**
 * Tails `GET /llm-events?cmd=` for one command. The SSE id is
 * `cmdIndex:attemptIndex:lineOffset:eventIndex`. An attempt change (a retried
 * command's stream picks up the newer attempt) resets the buffer; otherwise a
 * frame is dropped when its `(lineOffset, eventIndex)` pair is lexicographically
 * <= the last accepted one, so a reconnect replay is deduped while two sibling
 * events parsed from the same line (distinct eventIndex) are both kept. A frame
 * without a parseable 4-part id is appended as-is.
 *
 * Incoming frames are accumulated into a plain array (capped at
 * MAX_LLM_EVENTS events and MAX_LLM_BUFFER_CHARS in total, each event clipped
 * by clipLlmEvent) and published to React state at most once per animation
 * frame, rather than once per event — so a flood of frames costs one state
 * copy per frame tick, not one per event (CWE-400).
 */
export function useLlmEventStream(processId: number, cmd: number, enabled: boolean): LlmEventStreamState {
  const [state, setState] = useState<LlmEventStreamState>({ events: [], ended: false, truncated: false });

  useEffect(() => {
    if (!enabled) return;
    // eslint-disable-next-line react-hooks/set-state-in-effect -- reset buffer on (re)subscribe
    setState({ events: [], ended: false, truncated: false });

    const cursor: FrameCursor = { attempt: -1, lineOffset: -1, eventIndex: -1 };
    const buffer = createLlmEventBuffer(setState);

    const path = `/api/v1/stream/processes/${processId}/llm-events?cmd=${cmd}`;
    const close = openStream(path, {
      onEvent(name, data, id) {
        if (name === 'llm.event') {
          const resetBuffer = advanceCursor(cursor, id);
          if (resetBuffer === null) return;
          if (resetBuffer) buffer.reset();
          const { event, size } = frameEvent(data as LlmStreamFrame);
          buffer.push(event, size);
        } else if (name === 'llm.end') {
          buffer.end();
        }
      },
    });
    return () => {
      buffer.dispose();
      close();
    };
  }, [processId, cmd, enabled]);

  return state;
}
