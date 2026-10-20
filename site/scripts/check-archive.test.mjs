import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { linkSync, mkdirSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { test } from 'node:test'
import { gzipSync } from 'node:zlib'
import { checkArchive } from './check-archive.mjs'

// One tar member: a ustar header for [name] of [type] with [body], padded to whole blocks.
function member(name, type = '0', body = '', extra = {}) {
  const header = Buffer.alloc(512)
  header.write(name, 0, 100)
  header.write('0000644\0', 100)
  header.write(`${Buffer.byteLength(body).toString(8).padStart(11, '0')}\0`, 124)
  header.write(type, 156)
  if (extra.link) header.write(extra.link, 157, 100)
  header.write('ustar\0' + '00', 257)
  if (extra.prefix) header.write(extra.prefix, 345, 155)
  const data = Buffer.alloc(Math.ceil(Buffer.byteLength(body) / 512) * 512)
  data.write(body)
  return Buffer.concat([header, data])
}
const archive = (...members) => gzipSync(Buffer.concat([...members, Buffer.alloc(1024)]))

// A pax record "<length> <key>=<value>\n", whose length counts itself.
function pax(key, value) {
  const body = ` ${key}=${value}\n`
  let length = body.length + 1
  while (String(length).length + body.length !== length) length++
  return `${length}${body}`
}

// Archives [from] as release.yml does. busybox tar (the upstream CI job's node:alpine) has no --format
// and writes ustar already; COPYFILE_DISABLE keeps macOS tar from adding AppleDouble members.
function ustar(out, from) {
  const env = { ...process.env, COPYFILE_DISABLE: '1' }
  try {
    execFileSync('tar', ['--format=ustar', '-czf', out, '-C', from, '.'], { env, stdio: 'pipe' })
  } catch {
    execFileSync('tar', ['-czf', out, '-C', from, '.'], { env })
  }
}

test('files and directories with relative paths pass', () => {
  assert.deepEqual(checkArchive(archive(member('./', '5'), member('./index.html', '0', '<p>'), member('./assets/', '5'))), [])
})

test('links, devices and fifos are refused', () => {
  for (const [type, extra] of [['2', { link: '/home/runner/.git-credentials' }], ['1', { link: './index.html' }], ['3'], ['4'], ['6'], ['7']]) {
    const problems = checkArchive(archive(member('./x', type, '', extra ?? {})))
    assert.equal(problems.length, 1, type)
    assert.match(problems[0], /not a plain ustar file or directory/, type)
  }
})

test('pax, global, GNU long-name and sparse headers are refused, whatever name they carry', () => {
  for (const [type, body] of [
    ['x', pax('path', 'ok.txt')],
    ['x', pax('GNU.sparse.name', '/tmp/escaped/evil.txt')],
    ['g', pax('path', '../../x')],
    ['L', 'harmless.txt\0'],
    ['K', 'link-target\0'],
    ['S', ''],
  ]) {
    const problems = checkArchive(archive(member('header', type, body), member('ok.txt')))
    assert.match(problems.join('; '), /not a plain ustar file or directory/, `${type} ${body}`)
  }
})

test('absolute paths and paths that leave the archive are refused, prefix included', () => {
  assert.match(checkArchive(archive(member('/etc/cron.d/x')))[0], /absolute path/)
  assert.match(checkArchive(archive(member('./a/../../x')))[0], /leaves the archive/)
  assert.match(checkArchive(archive(member('..', '5')))[0], /leaves the archive/)
  assert.match(checkArchive(archive(member('evil.txt', '0', '', { prefix: '../..' })))[0], /leaves the archive/)
  assert.match(checkArchive(archive(member('evil.txt', '0', '', { prefix: '/etc' })))[0], /absolute path/)
})

test('a size field that is not octal is refused instead of ending the scan early or looping', () => {
  for (const size of ['garbage\0', '-1000\0', '\0']) {
    const bad = member('./a.html', '0', 'x')
    bad.fill(0, 124, 136)
    bad.write(size, 124)
    const problems = checkArchive(archive(bad, member('/etc/passwd')))
    assert.match(problems.join('; '), /unreadable size field/, JSON.stringify(size))
  }
})

test('a ustar archive from tar passes, and one holding a symlink or hard link does not', () => {
  const dir = mkdtempSync(join(tmpdir(), 'homer-archive-'))
  mkdirSync(join(dir, 'site', 'assets'), { recursive: true })
  writeFileSync(join(dir, 'site', 'index.html'), '<p>')
  ustar(join(dir, 'ok.tgz'), join(dir, 'site'))
  assert.deepEqual(checkArchive(readFileSync(join(dir, 'ok.tgz'))), [])
  symlinkSync('/etc/hosts', join(dir, 'site', 'hosts'))
  linkSync(join(dir, 'site', 'index.html'), join(dir, 'site', 'copy.html'))
  ustar(join(dir, 'bad.tgz'), join(dir, 'site'))
  const problems = checkArchive(readFileSync(join(dir, 'bad.tgz')))
  assert.ok(problems.some((p) => p.includes('hosts')), problems.join('; '))
  assert.ok(problems.some((p) => p.includes('html') && p.includes('type "1"')), problems.join('; '))
})
