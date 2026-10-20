import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('@monaco-editor/react', () => ({
  loader: { config: vi.fn() },
}));

vi.mock('monaco-editor', () => ({
  default: {},
}));

class FakeWorker {
  constructor(
    public url: URL | string,
    public options?: WorkerOptions,
  ) {}
  terminate(): void {}
}

describe('setupMonaco', () => {
  beforeEach(() => {
    vi.resetModules();
    // @ts-expect-error — jsdom doesn't ship Worker
    globalThis.Worker = FakeWorker;
    delete (window as unknown as { MonacoEnvironment?: unknown })
      .MonacoEnvironment;
  });

  it('installs MonacoEnvironment.getWorker returning dedicated workers per label', async () => {
    const { setupMonaco } = await import('./setup');
    setupMonaco();

    const env = (
      window as unknown as {
        MonacoEnvironment: {
          getWorker: (mid: string, label: string) => FakeWorker;
        };
      }
    ).MonacoEnvironment;

    expect(env).toBeDefined();
    expect(env.getWorker('x', 'json')).toBeInstanceOf(FakeWorker);
    expect(env.getWorker('x', 'yaml')).toBeInstanceOf(FakeWorker);
    expect(env.getWorker('x', 'editorWorkerService')).toBeInstanceOf(
      FakeWorker,
    );
  });

  it('is idempotent — calling twice keeps the first environment', async () => {
    const { setupMonaco } = await import('./setup');
    setupMonaco();
    const first = (window as unknown as { MonacoEnvironment: unknown })
      .MonacoEnvironment;
    setupMonaco();
    const second = (window as unknown as { MonacoEnvironment: unknown })
      .MonacoEnvironment;
    expect(second).toBe(first);
  });
});
