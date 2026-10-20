/**
 * Module-scoped EventTarget. Non-React code (the API client, SSE)
 * dispatches an 'expired' event when it sees a 401; React code
 * subscribes once via useAuthExpiredHandler to perform logout +
 * redirect. Audit P0 #6.
 *
 * Module scope (not window-global) so SSR import doesn't crash and
 * tests get a fresh instance per module reset.
 */
const target = new EventTarget();

const AUTH_EXPIRED_EVENT = 'auth-expired';

export type AuthExpiredReason = 'http-401' | 'sse-401' | 'manual';

export function notifyAuthExpired(reason: AuthExpiredReason): void {
  target.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT, { detail: { reason } }));
}

export function onAuthExpired(
  handler: (reason: AuthExpiredReason) => void,
): () => void {
  const listener = (e: Event) => {
    const reason = (e as CustomEvent).detail?.reason ?? 'manual';
    handler(reason);
  };
  target.addEventListener(AUTH_EXPIRED_EVENT, listener);
  return () => target.removeEventListener(AUTH_EXPIRED_EVENT, listener);
}
