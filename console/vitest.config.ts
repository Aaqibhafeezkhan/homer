import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import path from 'node:path';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./vitest.setup.ts'],
    css: false,
    // Only active under `--coverage` (npm run test:coverage); feeds scripts/crap.mjs.
    // Keep include/exclude in sync with SCORED_* in scripts/crap.mjs and mutate in stryker.config.mjs.
    coverage: {
      provider: 'v8',
      reporter: ['json', 'text-summary', 'cobertura'],
      reportsDirectory: 'coverage',
      include: ['app/**/*.{ts,tsx}', 'components/**/*.{ts,tsx}', 'lib/**/*.{ts,tsx}', 'middleware.ts'],
      exclude: ['**/*.test.*', 'components/ui/**', '**/*.d.ts'],
    },
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, '.'),
    },
  },
});
