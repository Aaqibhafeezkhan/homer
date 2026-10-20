// Mutation testing (check `mutation-console`, ADR-0082), run through scripts/mutation.mjs.
// `mutate` matches the coverage scope in vitest.config.ts; `break: null` keeps it report-only.
const OUTPUT_DIR = 'reports/quality/mutation-console';

const config = {
  testRunner: 'vitest',
  // One Vitest process per runner; the default (cores - 1) saturates a developer machine.
  concurrency: 6,
  vitest: { configFile: 'vitest.stryker.config.ts', related: true },
  mutate: [
    'app/**/*.{ts,tsx}',
    'components/**/*.{ts,tsx}',
    'lib/**/*.{ts,tsx}',
    'middleware.ts',
    '!**/*.test.*',
    '!components/ui/**',
  ],
  reporters: ['html', 'json', 'clear-text', 'progress-append-only'],
  clearTextReporter: { reportTests: false, reportMutants: false },
  htmlReporter: { fileName: `${OUTPUT_DIR}/mutation.html` },
  jsonReporter: { fileName: `${OUTPUT_DIR}/mutation.json` },
  incremental: true,
  incrementalFile: `${OUTPUT_DIR}/stryker-incremental.json`,
  thresholds: { high: 80, low: 60, break: null },
  ignorePatterns: ['.next', 'coverage', 'reports', 'public/plugins', 'public/themes'],
};

export default config;
