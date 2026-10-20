import { appConfig } from '@/lib/config/app-config';

/**
 * Auth mode for a single Homer instance. Mirrors the cookie/bearer split of
 * `appConfig.auth.mode`; `disabled` is a deployment-wide concern carried by
 * `appConfig.auth.enabled`, not a per-instance value.
 */
export type InstanceMode = 'cookie' | 'bearer';

export interface Instance {
  id: string;
  label: string;
  baseUrl: string;
  mode: InstanceMode;
}

export interface InstancesState {
  instances: Instance[];
  activeInstanceId: string;
}

/** localStorage key holding the serialized {@link InstancesState}. */
const INSTANCES_KEY = 'homer_instances';

/** Id/label of the instance seeded from the deployment's build/runtime config. */
export const DEFAULT_INSTANCE_ID = 'default';

/**
 * Per-instance bearer-token localStorage key. Namespacing by instance id keeps
 * tokens for different backends from clobbering each other (the legacy
 * single-instance console used the bare `appConfig.auth.tokenKey`).
 */
export function tokenKeyFor(id: string): string {
  return `${appConfig.auth.tokenKey}::${id}`;
}

function seedDefault(): InstancesState {
  const baseUrl = appConfig.api?.baseUrl ?? '';
  const mode: InstanceMode = appConfig.auth?.mode === 'bearer' ? 'bearer' : 'cookie';
  return {
    instances: [{ id: DEFAULT_INSTANCE_ID, label: 'Default', baseUrl, mode }],
    activeInstanceId: DEFAULT_INSTANCE_ID,
  };
}

/**
 * Copy a pre-existing legacy bearer token (stored under the un-namespaced
 * `appConfig.auth.tokenKey`) into the seeded default instance's namespaced key
 * so users upgrading from the single-instance console are not silently logged
 * out. Idempotent: only runs when the namespaced key is still empty.
 */
function migrateLegacyToken(): void {
  if (typeof window === 'undefined') return;
  const legacy = localStorage.getItem(appConfig.auth.tokenKey);
  const namespaced = tokenKeyFor(DEFAULT_INSTANCE_ID);
  if (legacy !== null && localStorage.getItem(namespaced) === null) {
    localStorage.setItem(namespaced, legacy);
  }
}

function isValidState(value: unknown): value is InstancesState {
  if (typeof value !== 'object' || value === null) return false;
  const s = value as Partial<InstancesState>;
  if (!Array.isArray(s.instances) || s.instances.length === 0) return false;
  if (typeof s.activeInstanceId !== 'string') return false;
  return s.instances.every(
    (i) =>
      typeof i?.id === 'string' &&
      typeof i?.label === 'string' &&
      typeof i?.baseUrl === 'string' &&
      (i?.mode === 'cookie' || i?.mode === 'bearer'),
  );
}

function persist(state: InstancesState): void {
  if (typeof window === 'undefined') return;
  localStorage.setItem(INSTANCES_KEY, JSON.stringify(state));
}

function readState(): InstancesState {
  if (typeof window === 'undefined') return seedDefault();
  const raw = localStorage.getItem(INSTANCES_KEY);
  if (raw) {
    try {
      const parsed: unknown = JSON.parse(raw);
      if (isValidState(parsed)) {
        // Guarantee the active id still resolves to an instance.
        if (parsed.instances.some((i) => i.id === parsed.activeInstanceId)) {
          return parsed;
        }
        return { ...parsed, activeInstanceId: parsed.instances[0].id };
      }
    } catch {
      /* corrupt — fall through to a fresh seed */
    }
  }
  const seeded = seedDefault();
  migrateLegacyToken();
  persist(seeded);
  return seeded;
}

let cached: InstancesState | null = null;
const listeners = new Set<() => void>();

function getState(): InstancesState {
  if (cached === null) cached = readState();
  return cached;
}

function commit(next: InstancesState): void {
  cached = next;
  persist(next);
  listeners.forEach((l) => l());
}

/* ---- React external-store contract ---- */

export function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

export function getSnapshot(): InstancesState {
  return getState();
}

/* ---- Reads ---- */

export function getInstances(): Instance[] {
  return getState().instances;
}

export function getActiveInstance(): Instance {
  const s = getState();
  return s.instances.find((i) => i.id === s.activeInstanceId) ?? s.instances[0];
}

/** Active instance's base URL — read per request by the API client / SSE. */
export function activeBaseUrl(): string {
  return getActiveInstance().baseUrl;
}

/** Active instance's auth mode — read per request by the API client / SSE. */
export function activeMode(): InstanceMode {
  return getActiveInstance().mode;
}

/* ---- Mutations ---- */

export function upsertInstance(instance: Instance): void {
  const s = getState();
  const idx = s.instances.findIndex((i) => i.id === instance.id);
  const instances =
    idx >= 0
      ? s.instances.map((i) => (i.id === instance.id ? instance : i))
      : [...s.instances, instance];
  commit({ ...s, instances });
}

export function setActiveInstance(id: string): void {
  const s = getState();
  if (!s.instances.some((i) => i.id === id) || s.activeInstanceId === id) return;
  commit({ ...s, activeInstanceId: id });
}

/* ---- Per-instance bearer token ---- */

export function getInstanceToken(id: string): string | null {
  if (typeof window === 'undefined') return null;
  return localStorage.getItem(tokenKeyFor(id));
}

export function setInstanceToken(id: string, token: string): void {
  if (typeof window === 'undefined') return;
  localStorage.setItem(tokenKeyFor(id), token);
}

function removeInstanceToken(id: string): void {
  if (typeof window === 'undefined') return;
  localStorage.removeItem(tokenKeyFor(id));
}

export function getActiveToken(): string | null {
  return getInstanceToken(getActiveInstance().id);
}

export function setActiveToken(token: string): void {
  setInstanceToken(getActiveInstance().id, token);
}

export function removeActiveToken(): void {
  removeInstanceToken(getActiveInstance().id);
}

/** Test-only: drop the in-memory snapshot + listeners so a fresh seed runs. */
export function resetInstancesStoreForTests(): void {
  cached = null;
  listeners.clear();
}
