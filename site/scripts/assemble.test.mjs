import assert from 'node:assert/strict'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { assemble, keptVersions } from './assemble.mjs'

function fixture(releases) {
  const dir = mkdtempSync(join(tmpdir(), 'homer-assemble-'))
  const site = (path, label) => {
    mkdirSync(join(path, 'schemas'), { recursive: true })
    writeFileSync(join(path, 'index.html'), label)
    writeFileSync(join(path, '404.html'), `404 ${label}`)
    writeFileSync(join(path, 'schemas', 'homer-agent.schema.json'), `{"v":"${label}"}`)
  }
  for (const version of releases) site(join(dir, 'releases', version), version)
  site(join(dir, 'next'), 'next')
  return { dir, args: { releasesDir: join(dir, 'releases'), nextDir: join(dir, 'next'), outDir: join(dir, 'out'), siteRoot: '/homer/' } }
}
const read = (dir, path) => readFileSync(join(dir, 'out', path), 'utf8')

test('releases sort by version, not by name, and the newest is latest', () => {
  const { dir, args } = fixture(['1.9.0', '1.10.0', '1.2.3'])
  const { versions, latest } = assemble(args)
  assert.deepEqual(versions, ['1.10.0', '1.9.0', '1.2.3'])
  assert.equal(latest, '1.10.0')
  assert.deepEqual(JSON.parse(read(dir, 'versions.json')), ['1.10.0', '1.9.0', '1.2.3', 'next'])
  assert.match(read(dir, 'index.html'), /url=\/homer\/1\.10\.0\//)
  assert.match(read(dir, 'atlas/index.html'), /url=\/homer\/1\.10\.0\/atlas\//)
  assert.equal(read(dir, '404.html'), '404 1.10.0')
})

test('each version keeps its own site and schemas; latest and next get schema paths too', () => {
  const { dir, args } = fixture(['1.0.0', '1.1.0'])
  assemble(args)
  assert.equal(read(dir, '1.0.0/index.html'), '1.0.0')
  assert.equal(read(dir, 'next/index.html'), 'next')
  assert.equal(read(dir, 'schemas/1.0.0/homer-agent.schema.json'), '{"v":"1.0.0"}')
  assert.equal(read(dir, 'schemas/latest/homer-agent.schema.json'), '{"v":"1.1.0"}')
  assert.equal(read(dir, 'schemas/next/homer-agent.schema.json'), '{"v":"next"}')
})

test('only the newest patch of each of the newest minor versions is kept', () => {
  const { dir, args } = fixture(['1.0.0', '1.1.0', '1.1.1', '1.2.0', '1.3.0', '1.3.2', '1.3.1'])
  assert.deepEqual(assemble({ ...args, keep: 3 }).versions, ['1.3.2', '1.2.0', '1.1.1'])
  assert.deepEqual(JSON.parse(read(dir, 'versions.json')), ['1.3.2', '1.2.0', '1.1.1', 'next'])
  for (const dropped of ['1.0.0', '1.1.0', '1.3.0', '1.3.1']) {
    assert.equal(existsSync(join(dir, 'out', dropped)), false, dropped)
    assert.equal(existsSync(join(dir, 'out', 'schemas', dropped)), false, dropped)
  }
})

test('before the first release the root redirects to next', () => {
  const { dir, args } = fixture([])
  assert.equal(assemble(args).latest, 'next')
  assert.match(read(dir, 'index.html'), /url=\/homer\/next\//)
  assert.match(read(dir, 'atlas/index.html'), /url=\/homer\/next\/atlas\//)
  assert.deepEqual(JSON.parse(read(dir, 'versions.json')), ['next'])
  assert.equal(read(dir, 'schemas/latest/homer-agent.schema.json'), '{"v":"next"}')
})

test('a release directory that is not a version is refused, so it cannot name a site path', () => {
  for (const bad of ['latest', 'next', '1.2', 'v1.2.3', '1.2.3-rc.1', '01.2.3x']) {
    const { dir, args } = fixture(['1.0.0'])
    mkdirSync(join(dir, 'releases', bad), { recursive: true })
    assert.throws(() => assemble(args), /Not a release version/, bad)
    assert.equal(existsSync(join(dir, 'out')), false, bad)
  }
  const { dir, args } = fixture(['1.0.0'])
  writeFileSync(join(dir, 'releases', '1.0.1'), 'not a site')
  assert.throws(() => assemble(args), /Not a release version/, 'a file')
})

test('a site root that is not a plain path is refused', () => {
  for (const bad of ['homer/', '/homer', '/ho mer/', '/"><script>/', 'https://x/']) {
    const { args } = fixture(['1.0.0'])
    assert.throws(() => assemble({ ...args, siteRoot: bad }), /Not a site root path/, bad)
  }
})

test('keptVersions picks what the site keeps, so the deploy fetches nothing else', () => {
  assert.deepEqual(keptVersions(['1.0.0', '1.1.0', '1.1.1', 'v2', '', '1.2.0'], 2), ['1.2.0', '1.1.1'])
  const script = fileURLToPath(new URL('./assemble.mjs', import.meta.url))
  const out = execFileSync('node', [script, '--kept'], { input: '1.3.0\n1.3.1\nnot-a-tag\n1.2.9\n' })
  assert.equal(out.toString(), '1.3.1\n1.2.9\n')
})
