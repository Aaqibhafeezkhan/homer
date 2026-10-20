// Generates the site's source tree (<site>/src) from the repository: the hand-written pages in
// <site>/pages, the published Markdown files (sources.mjs) and the copied assets. Fails on a link
// to a file the repository does not hold.
//
// Usage: node scripts/prepare.mjs [REPO_DIR SITE_DIR]   (default: this repository and site/)
// Env: DOCS_REF (the tag or branch built, default main), DOCS_REPO_URL.
import { cpSync, existsSync, lstatSync, mkdirSync, readdirSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import * as sources from './sources.mjs'
import { atlasPage } from './atlas.mjs'
import { REPO_ASSETS, transform } from './transform.mjs'

/**
 * Writes <site>/src and returns the published pages, plus every link to a missing file as
 * "<file>: <target>". The caller fails the build on a dead link.
 */
export function prepare({ repo, site, ref = 'main', repoUrl = 'https://github.com/livesport/homer', from = sources }) {
  const src = join(site, 'src')
  const published = [
    ...from.pages,
    ...readdirSync(join(repo, from.adrDir))
      .filter((name) => /^\d{4}-.*\.md$/.test(name))
      .sort()
      .map((name) => ({ from: `${from.adrDir}/${name}`, to: `adr/${name}` })),
  ]
  const siteOf = new Map(published.map((p) => [p.from, p.to]))
  // CWE-59 and CWE-22: a file the site publishes must be a regular file inside the repository,
  // reached without a link. A link at it or in a parent directory, or a path that leaves the
  // repository, could otherwise pull any file the build can read (~/.npmrc, ~/.ssh) into the
  // published site.
  const realRepo = realpathSync(repo)
  const regular = (repoPath) => {
    const full = join(repo, repoPath)
    if (!existsSync(full) || !lstatSync(full).isFile()) return false
    const real = realpathSync(full)
    return real.startsWith(realRepo + sep) && real === join(realRepo, repoPath)
  }
  const refused = 'not a regular file inside the repository'
  const kind = (repoPath) => {
    const full = join(repo, repoPath)
    if (!existsSync(full)) return undefined
    return statSync(full).isDirectory() ? 'dir' : 'file'
  }

  // CWE-59: the copy keeps a symlink, and the page, image and asset
  // writes below follow one, so site/pages as a link, or a link in it (`_repo`, `public`), would
  // let them write outside src. lstat, because readdirSync follows the root it is given.
  const pages = join(site, 'pages')
  if (lstatSync(pages).isSymbolicLink()) throw new Error('site/pages may not be a symlink')
  const linked = readdirSync(pages, { recursive: true, withFileTypes: true }).filter((e) => e.isSymbolicLink())
  if (linked.length > 0) {
    const names = linked.map((e) => join(e.parentPath, e.name).slice(pages.length + 1))
    throw new Error(`site/pages may hold no symlink: ${names.join(', ')}`)
  }
  rmSync(src, { recursive: true, force: true })
  cpSync(pages, src, { recursive: true })

  const deadLinks = []
  const images = new Map() // repository path → the first page that shows it
  for (const page of published) {
    if (!regular(page.from)) {
      deadLinks.push(`${page.from}: ${refused}`)
      continue
    }
    const { page: text, deadLinks: dead, images: used } = transform(readFileSync(join(repo, page.from), 'utf8'), {
      from: page.from,
      to: page.to,
      siteOf: (p) => siteOf.get(p),
      kind,
      repoUrl,
      ref,
    })
    deadLinks.push(...dead.map((target) => `${page.from}: ${target}`))
    for (const image of used) if (!images.has(image)) images.set(image, page.from)
    mkdirSync(dirname(join(src, page.to)), { recursive: true })
    writeFileSync(join(src, page.to), text)
  }
  const assets = resolve(src, REPO_ASSETS)
  for (const [image, page] of images) {
    // CWE-22: the copy must land inside the asset directory. transform() already refuses `..`
    // and backslashes, so the target check is a second line that only a change there would reach.
    if (!resolve(assets, image).startsWith(assets + sep) || !regular(image)) {
      deadLinks.push(`${page}: ${image}: ${refused}`)
      continue
    }
    // Beside the pages, not in public/: the build resolves them, so a missing one fails it, and
    // each gets a content hash, so a new version never shows an old cached image.
    mkdirSync(dirname(join(src, REPO_ASSETS, image)), { recursive: true })
    cpSync(join(repo, image), join(src, REPO_ASSETS, image))
  }
  for (const asset of from.assets) {
    if (!regular(asset.from)) {
      deadLinks.push(`${asset.from}: ${refused}`)
      continue
    }
    mkdirSync(dirname(join(src, 'public', asset.to)), { recursive: true })
    cpSync(join(repo, asset.from), join(src, 'public', asset.to))
  }
  if (from.atlas !== undefined) {
    if (regular(from.atlas)) {
      const { page, css, body, script } = atlasPage(readFileSync(join(repo, from.atlas), 'utf8'))
      mkdirSync(join(src, 'atlas'), { recursive: true })
      writeFileSync(join(src, 'atlas', 'index.md'), page)
      writeFileSync(join(src, 'atlas', 'atlas.css'), css)
      writeFileSync(join(src, 'atlas', 'body.html'), body)
      writeFileSync(join(src, 'atlas', 'run.mjs'), script)
    } else {
      deadLinks.push(`${from.atlas}: ${refused}`)
    }
  }
  return { published, deadLinks }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const here = join(dirname(fileURLToPath(import.meta.url)), '..')
  const [repo = join(here, '..'), site = here] = process.argv.slice(2)
  const ref = process.env.DOCS_REF || 'main'
  const { published, deadLinks } = prepare({ repo, site, ref, repoUrl: process.env.DOCS_REPO_URL || undefined })
  if (deadLinks.length > 0) {
    console.error(`Links to files the repository does not hold:\n  ${deadLinks.join('\n  ')}`)
    process.exit(1)
  }
  console.log(`Prepared ${published.length} pages for ${ref}.`)
}
