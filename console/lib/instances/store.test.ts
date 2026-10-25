import { describe, it, expect, beforeEach } from 'vitest';
import { appConfig } from '@/lib/config/app-config';
import {
  DEFAULT_INSTANCE_ID,
  activeBaseUrl,
  activeMode,
  getActiveInstance,
  getActiveToken,
  getInstanceToken,
  getInstances,
  getSnapshot,
  removeActiveToken,
  resetInstancesStoreForTests,
  setActiveInstance,
  setActiveToken,
  setInstanceToken,
  tokenKeyFor,
  upsertInstance,
  type Instance,
} from './store';

const INSTANCES_KEY = 'homer_instances';
const remote: Instance = {
  id: 'remote',
  label: 'Remote',
  baseUrl: 'https://homer-b.example.com',
  mode: 'bearer',
};

describe('instances store', () => {
  beforeEach(() => {
    resetInstancesStoreForTests();
  });

  it('seeds a single default instance from appConfig when nothing is stored', () => {
    const instances = getInstances();
    expect(instances).toHaveLength(1);
    expect(instances[0].id).toBe(DEFAULT_INSTANCE_ID);
    expect(instances[0].baseUrl).toBe(appConfig.api.baseUrl);
    expect(getActiveInstance().id).toBe(DEFAULT_INSTANCE_ID);
  });

  it('persists the seeded state to localStorage', () => {
    getSnapshot();
    const raw = localStorage.getItem(INSTANCES_KEY);
    expect(raw).not.toBeNull();
    expect(JSON.parse(raw!).activeInstanceId).toBe(DEFAULT_INSTANCE_ID);
  });

  it('upsert adds an instance and setActive switches base URL + mode', () => {
    upsertInstance(remote);
    expect(getInstances()).toHaveLength(2);

    setActiveInstance('remote');
    expect(getActiveInstance().id).toBe('remote');
    expect(activeBaseUrl()).toBe(remote.baseUrl);
    expect(activeMode()).toBe('bearer');
  });

  it('upsert replaces an existing instance by id', () => {
    upsertInstance(remote);
    upsertInstance({ ...remote, label: 'Renamed', mode: 'cookie' });
    const found = getInstances().filter((i) => i.id === 'remote');
    expect(found).toHaveLength(1);
    expect(found[0].label).toBe('Renamed');
    expect(found[0].mode).toBe('cookie');
  });

  it('setActiveInstance ignores an unknown id', () => {
    setActiveInstance('does-not-exist');
    expect(getActiveInstance().id).toBe(DEFAULT_INSTANCE_ID);
  });

  it('namespaces bearer tokens per instance', () => {
    upsertInstance(remote);
    setActiveToken('default-token');
    setInstanceToken('remote', 'remote-token');

    expect(localStorage.getItem(tokenKeyFor(DEFAULT_INSTANCE_ID))).toBe('default-token');
    expect(localStorage.getItem(tokenKeyFor('remote'))).toBe('remote-token');
    // Active token follows the active instance.
    expect(getActiveToken()).toBe('default-token');
    setActiveInstance('remote');
    expect(getActiveToken()).toBe('remote-token');
  });

  it('removeActiveToken clears only the active instance token', () => {
    upsertInstance(remote);
    setActiveToken('default-token');
    setInstanceToken('remote', 'remote-token');
    removeActiveToken();
    expect(getActiveToken()).toBeNull();
    expect(getInstanceToken('remote')).toBe('remote-token');
  });

  it('migrates a legacy un-namespaced bearer token into the default instance key', () => {
    // Simulate an upgrade from the single-instance console.
    localStorage.setItem(appConfig.auth.tokenKey, 'legacy-token');
    resetInstancesStoreForTests();

    // First store access triggers seed + migration.
    expect(getActiveToken()).toBe('legacy-token');
    expect(localStorage.getItem(tokenKeyFor(DEFAULT_INSTANCE_ID))).toBe('legacy-token');
  });

  it('does not overwrite an existing namespaced token during migration', () => {
    localStorage.setItem(appConfig.auth.tokenKey, 'legacy-token');
    localStorage.setItem(tokenKeyFor(DEFAULT_INSTANCE_ID), 'already-here');
    resetInstancesStoreForTests();

    expect(getActiveToken()).toBe('already-here');
  });

  it('falls back to a fresh seed when stored state is corrupt', () => {
    localStorage.setItem(INSTANCES_KEY, '{ not valid json');
    resetInstancesStoreForTests();
    expect(getInstances()).toHaveLength(1);
    expect(getActiveInstance().id).toBe(DEFAULT_INSTANCE_ID);
  });

  it('repairs an active id that no longer resolves to an instance', () => {
    localStorage.setItem(
      INSTANCES_KEY,
      JSON.stringify({
        instances: [{ id: 'only', label: 'Only', baseUrl: 'https://x', mode: 'cookie' }],
        activeInstanceId: 'ghost',
      }),
    );
    resetInstancesStoreForTests();
    expect(getActiveInstance().id).toBe('only');
  });

  it('seeds a cookie default when the deployment does not offer bearer', () => {
    expect(getActiveInstance().mode).toBe('cookie');
  });

  it('seeds a bearer default when the deployment offers bearer sign-in', () => {
    appConfig.auth.allowBearer = true;
    try {
      resetInstancesStoreForTests();
      expect(getActiveInstance().mode).toBe('bearer');
    } finally {
      appConfig.auth.allowBearer = false;
    }
  });

  it('seeds a bearer default when the deployment mode is bearer', () => {
    appConfig.auth.mode = 'bearer';
    try {
      resetInstancesStoreForTests();
      expect(getActiveInstance().mode).toBe('bearer');
    } finally {
      appConfig.auth.mode = 'cookie';
    }
  });

  it('never rewrites the mode of a persisted instance', () => {
    localStorage.setItem(
      INSTANCES_KEY,
      JSON.stringify({
        instances: [{ id: 'only', label: 'Only', baseUrl: 'https://x', mode: 'cookie' }],
        activeInstanceId: 'only',
      }),
    );
    appConfig.auth.allowBearer = true;
    try {
      resetInstancesStoreForTests();
      expect(getActiveInstance().mode).toBe('cookie');
    } finally {
      appConfig.auth.allowBearer = false;
    }
  });
});
