import { mergeConfig } from 'vitest/config';
import base from './vitest.config';

// Stryker's per-test coverage instrumentation slows CPU-heavy tests far more than V8 coverage
// does: raise the default timeout, and leave out the one stress test that still overruns its own
// explicit 30 s timeout — a failing dry run aborts the whole mutation run. See ADR-0082.
export default mergeConfig(base, {
  test: {
    testTimeout: 120_000,
    testNamePattern: /^(?!.*evicts malformed frames past MAX_LLM_EVENTS)/,
  },
});
