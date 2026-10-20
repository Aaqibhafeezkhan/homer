import { readdirSync } from 'node:fs'
import { defineConfig } from 'vitepress'
import { tableWidths } from './table-widths.mjs'

// DOCS_BASE is the path the build is served under, e.g. /homer/1.34.0/ or /homer/next/ on GitHub
// Pages; DOCS_VERSION labels it in the version menu.
const base = process.env.DOCS_BASE || '/'
const version = process.env.DOCS_VERSION || 'dev'
const repoUrl = process.env.DOCS_REPO_URL || 'https://github.com/livesport/homer'

const adrs = readdirSync(new URL('../src/adr/', import.meta.url))
  .filter((name) => /^\d{4}-.*\.md$/.test(name))
  .sort()
  .map((name) => ({ text: name.slice(0, 4), link: `/adr/${name.replace(/\.md$/, '')}` }))

export default defineConfig({
  title: 'Homer',
  description: 'An asynchronous execution orchestrator for agents: shell, Claude Code, Codex and LangGraph.',
  srcDir: 'src',
  base,
  lang: 'en',
  lastUpdated: false,
  // The docs show how to reach a local stack (http://localhost:3000); those are not site links.
  ignoreDeadLinks: 'localhostLinks',
  head: [['meta', { name: 'homer-docs-version', content: version }]],
  markdown: { config: tableWidths },
  themeConfig: {
    search: { provider: 'local' },
    nav: [
      { text: 'Guide', link: '/guide/getting-started' },
      { text: 'Agents', link: '/agents/reference' },
      { text: 'Operate', link: '/operate/server' },
      { text: 'Feature atlas', link: '/atlas/' },
      { text: 'Case studies', link: '/case-studies/' },
      { text: 'Security', link: '/security' },
      { text: 'Contributing', link: '/contributing/' },
    ],
    sidebar: {
      '/adr/': [{ text: 'Decision records', link: '/adr/', items: adrs }],
      '/': [
        {
          text: 'Guide',
          items: [
            { text: 'Getting started', link: '/guide/getting-started' },
            { text: 'Feature atlas', link: '/atlas/' },
            { text: 'Backend', link: '/guide/backend' },
          ],
        },
        {
          text: 'Agents',
          items: [
            { text: 'Agent reference', link: '/agents/reference' },
            { text: 'Debugging agents', link: '/agents/debugging' },
            { text: 'LangGraph workflows', link: '/agents/langgraph' },
            { text: 'JSON schemas', link: '/schemas' },
          ],
        },
        {
          text: 'Operate',
          items: [
            { text: 'Server configuration', link: '/operate/server' },
            { text: 'Docker deployment', link: '/operate/docker' },
            { text: 'Console', link: '/operate/console' },
            { text: 'Console deployment', link: '/operate/console-deployment' },
            { text: 'Console plugins', link: '/operate/console-plugins' },
          ],
        },
        {
          text: 'Case studies',
          link: '/case-studies/',
          collapsed: true,
          items: [
            {
              text: 'Workflows and people',
              items: [
                { text: 'Ticket to merge request', link: '/case-studies/durable-pipeline' },
                { text: 'Keeping long runs', link: '/case-studies/long-run-salvage' },
                { text: 'Questions from chat', link: '/case-studies/chat-questions' },
                { text: 'Review → fix loop', link: '/case-studies/review-loop' },
                { text: 'Self-improving factory', link: '/case-studies/self-improving-factory' },
              ],
            },
            {
              text: 'Security and isolation',
              items: [
                { text: 'Credential proxy', link: '/case-studies/credential-proxy' },
                { text: 'Public chat intake', link: '/case-studies/chat-intake' },
                { text: 'Containing a Claude step', link: '/case-studies/claude-tool-limits' },
                { text: 'Decisions with proof', link: '/case-studies/proof-carrying-decisions' },
                { text: 'Agent as an MCP tool', link: '/case-studies/agent-as-mcp-tool' },
              ],
            },
            {
              text: 'Capacity and scheduling',
              items: [
                { text: 'Runner pools', link: '/case-studies/runner-pools' },
                { text: 'Cross-agent locks', link: '/case-studies/cross-agent-locks' },
                { text: 'One lane at a time', link: '/case-studies/single-lane' },
                { text: 'Warm caches', link: '/case-studies/warm-caches' },
                { text: 'macOS build hosts', link: '/case-studies/macos-build-hosts' },
              ],
            },
            {
              text: 'Operations',
              items: [
                { text: 'Agent releases', link: '/case-studies/agent-releases' },
                { text: 'Linting agents', link: '/case-studies/agent-linting' },
                { text: 'Cost through a gateway', link: '/case-studies/cost-accounting' },
              ],
            },
          ],
        },
        {
          text: 'Project',
          items: [
            { text: 'Security', link: '/security' },
            { text: 'Contributing', link: '/contributing/' },
            { text: 'Quality checks', link: '/contributing/quality-checks' },
            { text: 'Decision records', link: '/adr/' },
            { text: 'Changelog', link: '/changelog' },
          ],
        },
      ],
    },
    socialLinks: [{ icon: 'github', link: repoUrl }],
    outline: { level: [2, 3] },
    footer: { message: `Released under the Apache License 2.0. Documentation for ${version}.` },
  },
})
