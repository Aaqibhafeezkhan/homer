import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { atlasPage } from './atlas.mjs'

const html = (body) => `<!doctype html><html><head><style>.atlas h1 { color: red; }</style></head>
<body class="atlas">${body}</body></html>`

test('the atlas becomes a site page that imports its styles, body and script', () => {
  const { page, css, body, script } = atlasPage(html('<h1>Atlas</h1>\n<script>console.log(1)</script>\n'))
  assert.match(page, /^---\nlayout: page\nsidebar: false\ntitle: Feature atlas\n---\n/)
  assert.match(page, /<div class="atlas" v-html="body"><\/div>/)
  assert.equal(css, '.atlas h1 { color: red; }')
  assert.equal(body, '<h1>Atlas</h1>\n')
  assert.equal(script, 'export default function run() {console.log(1)}\n')
})

test('an atlas that is not shaped as expected fails the build', () => {
  assert.throws(() => atlasPage('<html><body><h1>x</h1></body></html>'), /one <style> block/)
  assert.throws(() => atlasPage(html('<h1>no script</h1>')), /one <script> in the body/)
  assert.throws(() => atlasPage(html('<script>a()</script><script>b()</script>')), /one <script> in the body, found 2/)
  assert.throws(() => atlasPage(html('<script>a()</script><SCRIPT src="x.js"></SCRIPT>')), /a second <script>/)
})

test("the repository's atlas converts, and every style rule is scoped under .atlas", () => {
  const { css } = atlasPage(readFileSync(new URL('../../docs/homer-feature-atlas.html', import.meta.url), 'utf8'))
  const selectors = [...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{/g)]
    .map((m) => m[1].trim())
    .filter((s) => !s.startsWith('@'))
  assert.ok(selectors.length > 50)
  for (const selector of selectors) {
    for (const part of selector.split(',')) assert.match(part, /\.atlas\b/, selector)
  }
})
