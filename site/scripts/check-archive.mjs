// Refuses a release's docs archive unless it is plain ustar holding only regular files and
// directories with relative paths; extension headers are refused, never parsed (why: ADR-0087).
// pages.yml runs this before extracting, so a tampered archive cannot write outside its directory
// (CWE-22).
//
// Usage: node scripts/check-archive.mjs ARCHIVE.tar.gz   (exit 1 and the reasons on a refusal)
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { gunzipSync } from 'node:zlib'

const BLOCK = 512
const FILE = new Set(['0', '\0'])
const DIR = '5'

/** The reasons to refuse the gzipped tar in [gz]; empty when it may be extracted. */
export function checkArchive(gz) {
  const tar = gunzipSync(gz)
  const problems = []
  for (let at = 0; at + BLOCK <= tar.length; ) {
    const start = at
    const header = tar.subarray(at, at + BLOCK)
    if (header.every((b) => b === 0)) break
    // A size that is not plain octal cannot locate the next member, so nothing after it could be
    // checked: refuse rather than stop early with a clean result or loop on a negative size.
    const sizeText = field(header, 124, 12).trim()
    if (!/^[0-7]{1,11}$/.test(sizeText)) {
      problems.push(`member at byte ${start}: unreadable size field ${JSON.stringify(sizeText)}`)
      break
    }
    const size = parseInt(sizeText, 8)
    at += BLOCK + Math.ceil(size / BLOCK) * BLOCK
    const type = String.fromCharCode(header[156])
    const prefix = field(header, 345, 155)
    const name = prefix ? `${prefix}/${field(header, 0, 100)}` : field(header, 0, 100)
    if (!FILE.has(type) && type !== DIR) {
      problems.push(`${name}: not a plain ustar file or directory (type ${JSON.stringify(type)})`)
      continue
    }
    if (name.startsWith('/')) problems.push(`${name}: absolute path`)
    if (name.split('/').includes('..')) problems.push(`${name}: path leaves the archive`)
  }
  return problems
}

function field(header, offset, length) {
  const raw = header.subarray(offset, offset + length)
  const end = raw.indexOf(0)
  return raw.subarray(0, end < 0 ? length : end).toString('utf8')
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const problems = checkArchive(readFileSync(process.argv[2]))
  if (problems.length > 0) {
    console.error(`Refusing ${process.argv[2]}:\n  ${problems.join('\n  ')}`)
    process.exit(1)
  }
}
