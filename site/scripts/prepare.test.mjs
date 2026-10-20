import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, renameSync, rmSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { test } from 'node:test'
import { fileURLToPath } from 'node:url'
import { prepare } from './prepare.mjs'
import * as sources from './sources.mjs'

const from = {
  pages: [{ from: 'GUIDE.md', to: 'guide.md' }],
  adrDir: 'docs/adr',
  assets: [{ from: 'schema.json', to: 'schemas/schema.json' }],
  atlas: 'atlas.html',
}

// A repository with one guide, one ADR and an asset, and a site with one hand-written page.
function fixture(guide) {
  const dir = mkdtempSync(join(tmpdir(), 'homer-prepare-'))
  const repo = join(dir, 'repo')
  const site = join(dir, 'site')
  mkdirSync(join(repo, 'docs', 'adr'), { recursive: true })
  mkdirSync(join(site, 'pages'), { recursive: true })
  writeFileSync(join(repo, 'GUIDE.md'), guide)
  mkdirSync(join(repo, 'docs', 'assets'), { recursive: true })
  writeFileSync(join(repo, 'docs', 'assets', 'shot.png'), 'png')
  writeFileSync(join(repo, 'docs', 'adr', '0001-first.md'), '# ADR-0001\n\nSee the [guide](../../GUIDE.md).\n')
  writeFileSync(join(repo, 'schema.json'), '{}')
  writeFileSync(join(repo, 'atlas.html'), '<style>.atlas p {}</style><body class="atlas"><p>atlas</p><script>run()</script></body>')
  writeFileSync(join(site, 'pages', 'index.md'), '# Home\n')
  return { repo, site }
}

test('a clean repository yields every page and asset and no dead link', () => {
  const { repo, site } = fixture('# Guide\n\nRead [ADR-0001](docs/adr/0001-first.md).\n')
  const { published, deadLinks } = prepare({ repo, site, from })
  assert.deepEqual(deadLinks, [])
  assert.deepEqual(published.map((p) => p.to), ['guide.md', 'adr/0001-first.md'])
  assert.match(readFileSync(join(site, 'src', 'guide.md'), 'utf8'), /\]\(\.\/adr\/0001-first\.md\)/)
  assert.match(readFileSync(join(site, 'src', 'adr', '0001-first.md'), 'utf8'), /\]\(\.\.\/guide\.md\)/)
  assert.ok(existsSync(join(site, 'src', 'index.md')))
  assert.equal(readFileSync(join(site, 'src', 'public', 'schemas', 'schema.json'), 'utf8'), '{}')
  assert.match(readFileSync(join(site, 'src', 'atlas', 'index.md'), 'utf8'), /v-html="body"/)
  assert.equal(readFileSync(join(site, 'src', 'atlas', 'body.html'), 'utf8'), '<p>atlas</p>\n')
})

test('an image a page uses is copied into the site, and only that one', () => {
  const { repo, site } = fixture('# Guide\n\n![shot](docs/assets/shot.png)\n')
  prepare({ repo, site, from })
  assert.equal(readFileSync(join(site, 'src', '_repo', 'docs', 'assets', 'shot.png'), 'utf8'), 'png')
  assert.match(readFileSync(join(site, 'src', 'guide.md'), 'utf8'), /\]\(\.\/_repo\/docs\/assets\/shot\.png\)/)
})

test('an image reached through a link, or not a regular file, is refused and not copied', () => {
  const outside = mkdtempSync(join(tmpdir(), 'homer-outside-'))
  writeFileSync(join(outside, 'secret.png'), 'secret')
  for (const [name, plant] of [
    ['docs/assets/evil.png', (repo) => symlinkSync(join(outside, 'secret.png'), join(repo, 'docs/assets/evil.png'))],
    ['docs/linked/secret.png', (repo) => symlinkSync(outside, join(repo, 'docs/linked'))],
    // Not a directory, so the link pass calls it a file, but reading it would hang the build.
    ['docs/assets/pipe.png', (repo) => assert.equal(spawnSync('mkfifo', [join(repo, 'docs/assets/pipe.png')]).status, 0)],
  ]) {
    const { repo, site } = fixture(`# Guide\n\n![x](${name})\n`)
    plant(repo)
    const { deadLinks } = prepare({ repo, site, from })
    assert.deepEqual(deadLinks, [`GUIDE.md: ${name}: not a regular file inside the repository`], name)
    assert.equal(existsSync(join(site, 'src', '_repo', name)), false, name)
  }
  // A directory named like an image is linked on GitHub as a directory, never copied.
  const { repo, site } = fixture('# Guide\n\n![x](docs/assets/dir.png)\n')
  mkdirSync(join(repo, 'docs/assets/dir.png'))
  assert.deepEqual(prepare({ repo, site, from }).deadLinks, [])
  assert.equal(existsSync(join(site, 'src', '_repo', 'docs/assets/dir.png')), false)
  assert.match(readFileSync(join(site, 'src', 'guide.md'), 'utf8'), /github\.com\/livesport\/homer\/raw\/main\/docs\/assets\/dir\.png/)
})

test('a symlink in site/pages fails the build before anything is copied through it (CWE-59)', () => {
  for (const link of ['_repo', 'public', 'guide/deep']) {
    const outside = mkdtempSync(join(tmpdir(), 'homer-outside-'))
    const { repo, site } = fixture('# Guide\n\n![shot](docs/assets/shot.png)\n')
    mkdirSync(dirname(join(site, 'pages', link)), { recursive: true })
    symlinkSync(outside, join(site, 'pages', link))
    assert.throws(() => prepare({ repo, site, from }), new RegExp(`site/pages may hold no symlink: ${link}$`), link)
    assert.deepEqual(readdirSync(outside), [], link)
  }
})

test('site/pages as a symlink, live or dangling, fails the build before anything is written (CWE-59)', () => {
  for (const dangling of [false, true]) {
    const outside = mkdtempSync(join(tmpdir(), 'homer-outside-'))
    const { repo, site } = fixture('# Guide\n\n![shot](docs/assets/shot.png)\n')
    renameSync(join(site, 'pages'), join(outside, 'pages'))
    symlinkSync(dangling ? join(outside, 'gone') : join(outside, 'pages'), join(site, 'pages'))
    assert.throws(() => prepare({ repo, site, from }), /site\/pages may not be a symlink/, `dangling=${dangling}`)
    assert.deepEqual(readdirSync(outside), ['pages'], `dangling=${dangling}`)
    assert.deepEqual(readdirSync(join(outside, 'pages')), ['index.md'], `dangling=${dangling}`)
  }
})

test('a published page, ADR or asset reached through a link is refused and not published (CWE-59)', () => {
  const outside = mkdtempSync(join(tmpdir(), 'homer-outside-'))
  writeFileSync(join(outside, 'secret'), 'npm-token')
  mkdirSync(join(outside, 'adr'))
  writeFileSync(join(outside, 'adr', '0002-linked.md'), 'npm-token')
  for (const [name, plant] of [
    ['GUIDE.md', (repo) => symlinkSync(join(outside, 'secret'), join(repo, 'GUIDE.md'))],
    ['docs/adr/0002-linked.md', (repo) => symlinkSync(join(outside, 'secret'), join(repo, 'docs/adr/0002-linked.md'))],
    ['schema.json', (repo) => symlinkSync(join(outside, 'secret'), join(repo, 'schema.json'))],
    ['atlas.html', (repo) => symlinkSync(join(outside, 'secret'), join(repo, 'atlas.html'))],
  ]) {
    const { repo, site } = fixture('# Guide\n')
    rmSync(join(repo, name), { force: true })
    plant(repo)
    const { deadLinks } = prepare({ repo, site, from })
    assert.ok(deadLinks.includes(`${name}: not a regular file inside the repository`), `${name}: ${deadLinks}`)
    assert.equal(grep(join(site, 'src'), 'npm-token'), false, name)
  }
  // A path that leaves the repository is refused even without a link.
  {
    const { repo, site } = fixture('# Guide\n')
    writeFileSync(join(repo, '..', 'outside.md'), 'npm-token')
    const escaping = { ...from, pages: [...from.pages, { from: '../outside.md', to: 'outside.md' }] }
    assert.ok(prepare({ repo, site, from: escaping }).deadLinks.includes('../outside.md: not a regular file inside the repository'))
    assert.equal(grep(join(site, 'src'), 'npm-token'), false)
  }
  // A linked parent: every ADR in it is refused.
  const { repo, site } = fixture('# Guide\n')
  rmSync(join(repo, 'docs/adr'), { recursive: true })
  symlinkSync(join(outside, 'adr'), join(repo, 'docs/adr'))
  assert.deepEqual(prepare({ repo, site, from }).deadLinks, ['docs/adr/0002-linked.md: not a regular file inside the repository'])
  assert.equal(grep(join(site, 'src'), 'npm-token'), false)
})

// Whether any file under dir holds text.
function grep(dir, text) {
  return readdirSync(dir, { recursive: true, withFileTypes: true }).some(
    (e) => e.isFile() && readFileSync(join(e.parentPath, e.name), 'utf8').includes(text),
  )
}

test('a link to a missing file is reported with the file that holds it', () => {
  const { repo, site } = fixture('# Guide\n\nSee [gone](GONE.md).\n')
  assert.deepEqual(prepare({ repo, site, from }).deadLinks, ['GUIDE.md: GONE.md'])
})

test('the build step fails on a dead link and names it', () => {
  const { repo, site } = fixture('# Guide\n')
  writeFileSync(join(repo, 'README.md'), 'See [gone](GONE.md).\n')
  // The CLI reads the real source list, so give the fixture every file it names.
  for (const { from: name } of [...sources.pages, ...sources.assets]) {
    mkdirSync(join(repo, name, '..'), { recursive: true })
    if (!existsSync(join(repo, name))) writeFileSync(join(repo, name), '# Page\n')
  }
  const script = fileURLToPath(new URL('./prepare.mjs', import.meta.url))
  const run = spawnSync('node', [script, repo, site], { encoding: 'utf8' })
  assert.equal(run.status, 1)
  assert.match(run.stderr, /README\.md: GONE\.md/)
})
