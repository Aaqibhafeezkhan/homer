import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createMarkdownRenderer } from 'vitepress'
import { tableWidths } from './table-widths.mjs'

const md = await createMarkdownRenderer(process.cwd(), { config: tableWidths })
const prose = 'A sentence that explains a decision at length, the way an accepted-risk row does. '.repeat(3)
const heads = (html) => [...html.matchAll(/<th(?=[\s>])([^>]*)>/g)].map((m) => m[1])

test('a prose column gets a minimum width by its average length; short and code columns do not', () => {
  const html = md.render(
    `| Finding | Where | Why |\n|---|---|:--|\n| short | \`LineFilteringOutputStream\` | ${prose} |\n| short | \`TarStreams.addFile\` | ${prose} |\n`,
  )
  // 249 visible characters per cell → round(249 / 22) = 11rem; alignment kept.
  assert.deepEqual(heads(html), ['', '', ' style="text-align:left;min-width:11rem"'])
})

test('link targets do not count, and the width is clamped', () => {
  const link = `[ADR](${'https://example.com/'.repeat(10)})`
  const long = 'word '.repeat(200)
  const html = md.render(`| A | B |\n|---|---|\n| ${link} | ${long} |\n`)
  assert.deepEqual(heads(html), ['', ' style="min-width:20rem"'])
})

test('a table without body rows is left alone', () => {
  assert.deepEqual(heads(md.render('| A | B |\n|---|---|\n')), ['', ''])
})
