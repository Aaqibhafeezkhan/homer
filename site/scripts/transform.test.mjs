import assert from 'node:assert/strict'
import { test } from 'node:test'
import { transform } from './transform.mjs'

const repo = {
  'app/AGENT.md': 'file',
  'app/SERVER.md': 'file',
  'app/schemas': 'dir',
  'app/Main.kt': 'file',
  'docs/img.png': 'file',
}
const sites = { 'app/AGENT.md': 'agents/reference.md', 'app/SERVER.md': 'operate/server.md' }
const ctx = {
  from: 'app/SERVER.md',
  to: 'operate/server.md',
  siteOf: (p) => sites[p],
  kind: (p) => repo[p],
  repoUrl: 'https://github.com/livesport/homer',
  ref: '1.34.0',
}
const body = (md, c = ctx) => {
  const { page, deadLinks } = transform(md, c)
  return { text: page.replace(/^<div v-pre>\n\n/, '').replace(/\n\n<\/div>\n$/, ''), deadLinks }
}

test('a link to a published file points at its page, anchor kept', () => {
  assert.equal(body('[a](AGENT.md#steps)').text, '[a](../agents/reference.md#steps)')
})

test('a link to any other file points at it on GitHub at the built ref', () => {
  assert.equal(body('[k](Main.kt)').text, '[k](https://github.com/livesport/homer/blob/1.34.0/app/Main.kt)')
  assert.equal(body('[s](schemas/)').text, '[s](https://github.com/livesport/homer/tree/1.34.0/app/schemas)')
  assert.equal(body('[i](../docs/img.png)').text, '[i](https://github.com/livesport/homer/blob/1.34.0/docs/img.png)')
  assert.equal(body('<a href="Main.kt">k</a>').text, '<a href="https://github.com/livesport/homer/blob/1.34.0/app/Main.kt">k</a>')
  assert.equal(body('[r]: ../app/Main.kt').text, '[r]: https://github.com/livesport/homer/blob/1.34.0/app/Main.kt')
})

test('an image in the repository is served by the site, relative to the page', () => {
  const md = '![i](../docs/img.png) <img src="../docs/img.png" alt="i"> <source srcset="../docs/img.png">'
  const { page, images, deadLinks } = transform(md, ctx)
  assert.deepEqual(deadLinks, [])
  assert.match(page, /!\[i\]\(\.\.\/_repo\/docs\/img\.png\)/)
  assert.match(page, /<img src="\.\.\/_repo\/docs\/img\.png"/)
  assert.match(page, /<source srcset="\.\.\/_repo\/docs\/img\.png">/)
  assert.deepEqual(images, ['docs/img.png'])
  const top = transform('![i](docs/img.png)', { ...ctx, from: 'README.md', to: 'index.md' })
  assert.match(top.page, /!\[i\]\(\.\/_repo\/docs\/img\.png\)/)
  assert.deepEqual(top.deadLinks, [])
  // A page that moves deeper than its source: the rewritten target must not be resolved again.
  const deeper = transform('![i](docs/img.png)', { ...ctx, from: 'README.md', to: 'guide/getting-started.md' })
  assert.match(deeper.page, /!\[i\]\(\.\.\/_repo\/docs\/img\.png\)/)
  assert.deepEqual(deeper.deadLinks, [])
})

test('an image reached by a reference definition, or with a space in its name, is served by the site', () => {
  const repo2 = { ...repo, 'docs/img name.png': 'file' }
  const c = { ...ctx, kind: (p) => repo2[p], from: 'README.md', to: 'guide/getting-started.md' }
  const defined = transform('![i][shot]\n\n[shot]: docs/img.png', c)
  assert.match(defined.page, /\[shot\]: \.\.\/_repo\/docs\/img\.png/)
  assert.deepEqual(defined.images, ['docs/img.png'])
  // Decided on the decoded path without its fragment, as an inline image is.
  for (const target of ['docs/img.png#f', 'docs/img%2Epng']) {
    const { page, images } = transform(`[shot]: ${target}`, c)
    assert.match(page, /\[shot\]: \.\.\/_repo\/docs\/img\.png/, target)
    assert.deepEqual(images, ['docs/img.png'], target)
  }
  const spaced = transform('<img src="docs/img%20name.png">', c)
  assert.match(spaced.page, /src="\.\.\/_repo\/docs\/img%20name\.png"/)
  assert.deepEqual(spaced.images, ['docs/img name.png'])
})

test('a backslash path is dead, so it cannot leave the repository on Windows (CWE-22)', () => {
  // `kind` answers as a Windows file system would: the path resolves, so only the guard stops it.
  const windows = { ...ctx, kind: () => 'file' }
  const { deadLinks, images } = transform('![x](..%5C..%5Csecret.png) <img src="a\\..\\..\\b.png">', windows)
  assert.deepEqual(deadLinks, ['..%5C..%5Csecret.png', 'a\\..\\..\\b.png'])
  assert.deepEqual(images, [])
})

test('a link to a missing file or out of the repository is reported dead', () => {
  assert.deepEqual(body('[x](Missing.md) [y](../../etc/passwd)').deadLinks, ['Missing.md', '../../etc/passwd'])
  assert.deepEqual(body('[z](100%.md)').deadLinks, ['100%.md'])
})

test('external links, mail and in-page anchors are kept', () => {
  const md = '[w](https://example.com) [m](mailto:a@b.c) [h](#top) <https://example.com>'
  assert.equal(body(md).text, md)
})

test('code is left alone: fences and inline code keep links and tags', () => {
  const md = '```yaml\n- !<cmd>\n  run: "[x](Missing.md) {{INPUT}}"\n```\n`!<claude>` and `[x](Missing.md)`'
  const { text, deadLinks } = body(md)
  assert.equal(text, md)
  assert.deepEqual(deadLinks, [])
})

test('stray tags in prose are escaped, real HTML is kept', () => {
  assert.equal(body('use !<cmd> here').text, 'use !&lt;cmd> here')
  assert.equal(body('a <details><summary>s</summary></details> b<br>').text, 'a <details><summary>s</summary></details> b<br>')
  assert.equal(body('<!-- note -->').text, '<!-- note -->')
})

test('the page is wrapped in v-pre after any frontmatter, so {{ }} stays literal', () => {
  const { page } = transform('---\nstatus: proposed\n---\n\n# T {{X}}\n', { ...ctx, from: 'docs/adr/0001-a.md', to: 'adr/0001-a.md' })
  assert.match(page, /^---\nstatus: proposed\n---\n<div v-pre>\n\n\n# T \{\{X\}\}\n/)
  assert.match(page, /\n<\/div>\n$/)
})
