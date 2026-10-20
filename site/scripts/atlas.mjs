// Turns the feature atlas (docs/homer-feature-atlas.html, a standalone page) into a page of the
// documentation site, so it shares the site's navigation, search box, colour scheme and fonts.
// The atlas scopes its styles under `.atlas` and reads the site's theme variables, falling back
// to its own palette when opened on its own.

/**
 * @param {string} html the atlas file
 * @returns {{ page: string, css: string, body: string, script: string }} the Markdown page and the
 *   files it imports from its own directory: atlas.css, body.html and run.mjs.
 */
export function atlasPage(html) {
  const css = only(html, /<style>([\s\S]*?)<\/style>/g, 'one <style> block')
  const inner = only(html, /<body class="atlas">([\s\S]*?)<\/body>/g, 'one <body class="atlas">')
  const script = only(inner, /<script>([\s\S]*?)<\/script>/g, 'one <script> in the body')
  const body = inner.replace(/<script>[\s\S]*?<\/script>/, '').trim()
  if (/<script\b/i.test(body)) throw new Error('feature atlas: a second <script> in the body')
  const page = `---
layout: page
sidebar: false
title: Feature atlas
---

<script setup>
import { onMounted } from 'vue'
import body from './body.html?raw'
import run from './run.mjs'
import './atlas.css'

onMounted(run)
</script>

<div class="atlas" v-html="body"></div>
`
  // The script runs again on each visit: a client-side navigation mounts a fresh copy of the page.
  return { page, css, body: `${body}\n`, script: `export default function run() {${script}}\n` }
}

function only(text, pattern, what) {
  const found = [...text.matchAll(pattern)]
  if (found.length !== 1) throw new Error(`feature atlas: expected ${what}, found ${found.length}`)
  return found[0][1]
}
