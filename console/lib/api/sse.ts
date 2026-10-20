import { fetchEventSource, EventStreamContentType } from '@microsoft/fetch-event-source';
import { appConfig } from '@/lib/config/app-config';
import { activeBaseUrl, activeMode } from '@/lib/instances/store';
import { CSRF_HEADER, homerApi } from './client';
import { notifyAuthExpired } from '@/lib/auth/auth-expired-bus';

export interface SseHandlers {
  onEvent: (name: string, data: unknown, id?: string) => void;
  onError?: (err: unknown) => void;
  onOpen?: () => void;
}

// Audit P0 #7. The previous implementation returned nothing from
// onerror, which fetch-event-source treats as "use library default
// retry" — that retries forever with no cap and silently absorbs 401
// after token rotation, so the UI stays stuck on the last snapshot.
const BASE_DELAY_MS = 1000;
const MAX_DELAY_MS = 30_000;
const MAX_ATTEMPTS = 10;

class FatalSseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'FatalSseError';
  }
}

export function openStream(path: string, handlers: SseHandlers): () => void {
  const controller = new AbortController();
  const url = `${activeBaseUrl()}${path}`;

  // SSE GETs are session-authenticated too, so they must carry the CSRF header
  // (ADR-0016); harmless in bearer mode.
  const headers: Record<string, string> = { [CSRF_HEADER]: '1', ...homerApi.authorizationHeader() };

  let attempt = 0;

  void fetchEventSource(url, {
    headers,
    credentials: activeMode() === 'cookie' ? 'include' : undefined,
    signal: controller.signal,
    openWhenHidden: true,
    async onopen(res) {
      if (res.status === 401 && appConfig.auth.enabled) {
        notifyAuthExpired('sse-401');
        throw new FatalSseError('SSE 401 — token rotated or revoked');
      }
      const ct = res.headers.get('content-type');
      if (res.ok && ct && ct.includes(EventStreamContentType)) {
        attempt = 0;
        handlers.onOpen?.();
        return;
      }
      throw new Error(`SSE open failed: ${res.status} ${res.statusText}`);
    },
    onmessage(msg) {
      const name = msg.event || 'message';
      let parsed: unknown = msg.data;
      try {
        parsed = JSON.parse(msg.data);
      } catch {
        /* raw string */
      }
      handlers.onEvent(name, parsed, msg.id || undefined);
    },
    onerror(err) {
      // A FatalSseError from onopen (401) must stop retries before
      // surfacing to caller-supplied onError, which would otherwise
      // see the internal FatalSseError signal instead of treating
      // 401 as a logout event via the auth-expired bus.
      if (err instanceof FatalSseError) throw err;
      handlers.onError?.(err);
      attempt += 1;
      if (attempt > MAX_ATTEMPTS) {
        throw new Error(`SSE giving up after ${MAX_ATTEMPTS} retries`);
      }
      const delay = Math.min(BASE_DELAY_MS * 2 ** (attempt - 1), MAX_DELAY_MS);
      return delay;
    },
  });

  return () => controller.abort();
}
