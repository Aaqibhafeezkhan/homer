'use client';

import { useSyncExternalStore } from 'react';
import {
  getSnapshot,
  setActiveInstance,
  subscribe,
  upsertInstance,
  type Instance,
} from './store';

function useInstancesState() {
  // Same snapshot getter on server + client: SSR renders the seeded default,
  // the post-hydration client snapshot reflects localStorage.
  return useSyncExternalStore(subscribe, getSnapshot, getSnapshot);
}

export function useInstances(): {
  instances: Instance[];
  activeInstanceId: string;
  setActiveInstance: (id: string) => void;
  upsertInstance: (instance: Instance) => void;
} {
  const state = useInstancesState();
  return {
    instances: state.instances,
    activeInstanceId: state.activeInstanceId,
    setActiveInstance,
    upsertInstance,
  };
}

export function useActiveInstanceId(): string {
  return useInstancesState().activeInstanceId;
}
