import assert from 'node:assert/strict'
import { test } from 'node:test'
import { menuEntries, versionsRoot } from './versions.mjs'

test('versions.json is looked up one level above the version base', () => {
  assert.equal(versionsRoot('/homer/1.34.0/', 'https://livesport.github.io').href, 'https://livesport.github.io/homer/')
  assert.equal(versionsRoot('/group/sub/homer/next/', 'https://pages.example.com').href, 'https://pages.example.com/group/sub/homer/')
})

test('each listed version links to its directory under the root', () => {
  const root = new URL('https://livesport.github.io/homer/')
  assert.deepEqual(menuEntries(['1.34.0', 'next'], root), [
    { name: '1.34.0', path: '/homer/1.34.0/' },
    { name: 'next', path: '/homer/next/' },
  ])
})

test('anything but a version or next is left out, so versions.json cannot add arbitrary links', () => {
  const root = new URL('https://livesport.github.io/homer/')
  assert.deepEqual(menuEntries(['../../evil', 'https://evil.example/', 42, 'latest', '1.2'], root), [])
  assert.deepEqual(menuEntries({ not: 'a list' }, root), [])
})
