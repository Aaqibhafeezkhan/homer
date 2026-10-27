import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const css = readFileSync(new URL('./custom.css', import.meta.url), 'utf8')

// Selector and declarations of every rule, comments dropped; nested rules match on their own.
const rules = [...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)].map(
  ([, selector, body]) => ({ selector: selector.trim(), body }),
)

test('a code name in a table cell is never broken mid-word', () => {
  const rule = rules.find(({ selector }) => selector === '.vp-doc td code')
  assert.ok(rule, 'custom.css must keep the .vp-doc td code rule')
  assert.match(rule.body, /overflow-wrap:\s*normal\s*;/)
  assert.match(rule.body, /word-break:\s*normal\s*;/)
})

test('no rule lets a code name break at any character', () => {
  for (const { selector, body } of rules) {
    assert.doesNotMatch(body, /overflow-wrap:\s*anywhere/, selector)
    assert.doesNotMatch(body, /word-break:\s*break-all/, selector)
  }
})
