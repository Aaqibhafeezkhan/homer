/**
 * The one `git diff -U0` reader of the quality reports (ADR-0082): diff-coverage and the console's
 * mutation:changed both need "which lines did this change add or modify", and two parsers of the
 * same text drift on edge cases.
 */
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const git = (args, cwd) => execFileSync('git', ['-c', 'core.quotePath=false', ...args], { cwd, encoding: 'utf8', maxBuffer: 256 * 1024 * 1024 });

/**
 * `git diff -U0` of `base..HEAD` limited to `paths`, which are pathspecs relative to `cwd`. The
 * paths in the output are repo-relative whatever `cwd` is, and unquoted (core.quotePath=false).
 * A null `head` diffs against the working tree instead, uncommitted edits included.
 */
export function gitDiff(base, paths, cwd = process.cwd(), head = 'HEAD') {
  return git(['diff', '-U0', '--no-color', '--no-ext-diff', base, ...(head ? [head] : []), '--', ...paths], cwd);
}

/** Repo-relative paths of the untracked, not ignored files under `paths` (pathspecs relative to `cwd`). */
export function untrackedFiles(paths, cwd = process.cwd()) {
  return git(['ls-files', '--others', '--exclude-standard', '--full-name', '-z', '--', ...paths], cwd).split('\0').filter(Boolean);
}

/**
 * The lines the working tree adds or changes against `base`, as `changedLines` maps them: the
 * diff mode's view of a change still being written (changed.mjs). Every line of an untracked
 * file is new, since `git diff` does not see one.
 */
export function worktreeChanges(base, paths, cwd = process.cwd()) {
  const changed = changedLines(gitDiff(base, paths, cwd, null));
  const root = git(['rev-parse', '--show-toplevel'], cwd).trim();
  for (const file of untrackedFiles(paths, cwd)) {
    const text = fs.readFileSync(path.join(root, file), 'utf8');
    const count = text.split('\n').length - (text.endsWith('\n') ? 1 : 0);
    changed.set(file, new Set(Array.from({ length: count }, (_, i) => i + 1)));
  }
  return changed;
}

/** Repo-relative paths the working tree changes against `base`: edited, added, deleted and untracked. */
export function changedFiles(base, cwd = process.cwd()) {
  const tracked = git(['diff', '--name-only', '-z', '--no-renames', base, '--'], cwd).split('\0').filter(Boolean);
  return [...new Set([...tracked, ...untrackedFiles(['.'], cwd)])].sort();
}

/**
 * `git diff -U0` → Map(repo path → Set of added/changed line numbers in the new version).
 * A file header is only read between a `diff --git` line and the file's first hunk, so an added
 * line that happens to start with `+++ ` is never taken for one. A path git still quotes (a quote,
 * backslash or control character in it) cannot be read reliably, so it is an error, not a skip.
 */
export function changedLines(diff) {
  const files = new Map();
  let current = null;
  let inHeader = false;
  for (const line of diff.split('\n')) {
    if (line.startsWith('diff --git ')) {
      current = null;
      inHeader = true;
      continue;
    }
    if (inHeader && line.startsWith('+++ ')) {
      if (line.startsWith('+++ "')) throw new Error(`cannot read the quoted diff path in: ${line}`);
      current = line === '+++ /dev/null' ? null : line.slice(line.startsWith('+++ b/') ? 6 : 4);
      if (current && !files.has(current)) files.set(current, new Set());
      continue;
    }
    const hunk = /^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@/.exec(line);
    if (!hunk) continue;
    inHeader = false;
    if (!current) continue;
    const start = Number(hunk[1]);
    const count = hunk[2] === undefined ? 1 : Number(hunk[2]);
    for (let n = start; n < start + count; n++) files.get(current).add(n);
  }
  return files;
}
