// Console layering from console/CLAUDE.md § Directory layout and § Data flow, reported by
// `npm run quality:report` (check `architecture-console`, ADR-0082). Severity stays "warn" while the
// check is report-only. Tests are not cruised: they may import anything.
const LOCAL = '^(app|components|lib|types|config)/';

const config = {
  forbidden: [
    {
      name: 'lib-not-to-ui',
      comment: 'lib/ is the data and utility layer under the UI: it must not import components/ or app/.',
      severity: 'warn',
      from: { path: '^lib/' },
      to: { path: '^(components|app)/' },
    },
    {
      name: 'components-not-to-app',
      comment: 'Routes compose components, never the other way round.',
      severity: 'warn',
      from: { path: '^components/' },
      to: { path: '^app/' },
    },
    {
      name: 'ui-is-leaf',
      comment: 'components/ui holds shadcn primitives: other primitives and lib/utils/cn only.',
      severity: 'warn',
      from: { path: '^components/ui/' },
      to: { path: LOCAL, pathNot: ['^components/ui/', '^lib/utils/cn[.]ts$'] },
    },
    {
      name: 'types-import-nothing',
      comment: 'types/homer.ts hand-mirrors backend DTOs and depends on nothing.',
      severity: 'warn',
      from: { path: '^types/homer[.]ts$' },
      to: {},
    },
    {
      name: 'no-circular',
      severity: 'warn',
      from: {},
      to: { circular: true },
    },
  ],
  options: {
    doNotFollow: { path: 'node_modules' },
    exclude: { path: '[.]test[.](ts|tsx)$' },
    tsPreCompilationDeps: true,
    tsConfig: { fileName: 'tsconfig.json' },
    enhancedResolveOptions: {
      exportsFields: ['exports'],
      conditionNames: ['import', 'require', 'node', 'default', 'types'],
      mainFields: ['module', 'main', 'types', 'typings'],
    },
  },
};

export default config;
