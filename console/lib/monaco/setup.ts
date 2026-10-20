'use client';

import { loader } from '@monaco-editor/react';
import * as monaco from 'monaco-editor';

declare global {
  interface Window {
    MonacoEnvironment?: {
      getWorker: (moduleId: string, label: string) => Worker;
    };
  }
}

let configured = false;

export function setupMonaco(): void {
  if (configured || typeof window === 'undefined') return;
  configured = true;

  window.MonacoEnvironment = {
    getWorker(_moduleId: string, label: string): Worker {
      switch (label) {
        case 'json':
          return new Worker(
            new URL(
              'monaco-editor/esm/vs/language/json/json.worker',
              import.meta.url,
            ),
            { type: 'module' },
          );
        case 'yaml':
          return new Worker(
            new URL('monaco-yaml/yaml.worker', import.meta.url),
            { type: 'module' },
          );
        default:
          return new Worker(
            new URL(
              'monaco-editor/esm/vs/editor/editor.worker',
              import.meta.url,
            ),
            { type: 'module' },
          );
      }
    },
  };

  loader.config({ monaco });
}
