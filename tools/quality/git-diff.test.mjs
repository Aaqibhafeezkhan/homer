import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { changedFiles, changedLines, gitDiff, worktreeChanges } from './git-diff.mjs';

const section = (file, ...rest) => [`diff --git a/${file} b/${file}`, 'index 1..2 100644', `--- a/${file}`, `+++ b/${file}`, ...rest];

test('added and changed lines come from the new side of each hunk; deletions add none', () => {
  const diff = [
    ...section('app/app/src/main/kotlin/A.kt', '@@ -3,0 +4,2 @@ fun f() {', '+a', '+b', '@@ -10 +12 @@', '-x', '+y', '@@ -20,3 +23,0 @@'),
    'diff --git a/console/gone.ts b/console/gone.ts',
    'deleted file mode 100644',
    '--- a/console/gone.ts',
    '+++ /dev/null',
    '@@ -1,2 +0,0 @@',
  ].join('\n');
  assert.deepEqual(changedLines(diff), new Map([['app/app/src/main/kotlin/A.kt', new Set([4, 5, 12])]]));
});

test('an added line starting with +++ inside a hunk is content, not a file header', () => {
  const diff = section('console/lib/a.ts', '@@ -1,0 +2,2 @@', '+++ b/console/lib/other.ts', '+x').join('\n');
  assert.deepEqual(changedLines(diff), new Map([['console/lib/a.ts', new Set([2, 3])]]));
});

test('a path git still quotes is an error rather than a silently skipped file', () => {
  const diff = ['diff --git "a/console/lib/a\\"b.ts" "b/console/lib/a\\"b.ts"', '--- "a/console/lib/a\\"b.ts"', '+++ "b/console/lib/a\\"b.ts"', '@@ -1 +1 @@'].join('\n');
  assert.throws(() => changedLines(diff), /quoted diff path/);
});

test('an unquoted non-ASCII path (core.quotePath=false) is read as is', () => {
  const diff = section('console/lib/café.ts', '@@ -1 +1 @@').join('\n');
  assert.deepEqual(changedLines(diff), new Map([['console/lib/café.ts', new Set([1])]]));
});

test('gitDiff limits the diff to cwd-relative pathspecs and prints repo-relative, unquoted paths', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'git-diff-'));
  const git = (...args) => execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { cwd: root, encoding: 'utf8' });
  const write = (file, text) => {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), text);
  };
  try {
    git('init', '-q');
    write('console/lib/café.ts', 'a\nb\n');
    write('app/src/x.kt', 'x\n');
    git('add', '.');
    git('commit', '-q', '-m', 'base');
    const base = git('rev-parse', 'HEAD').trim();
    write('console/lib/café.ts', 'a\nB\nc\n');
    write('app/src/x.kt', 'y\n');
    git('commit', '-q', '-am', 'change');

    // As mutation:changed calls it: from the console directory, pathspec `.`.
    const diff = gitDiff(base, ['.'], path.join(root, 'console'));
    assert.match(diff, /^\+\+\+ b\/console\/lib\/café\.ts$/m);
    assert.doesNotMatch(diff, /app\/src\/x\.kt/);
    assert.deepEqual(changedLines(diff), new Map([['console/lib/café.ts', new Set([2, 3])]]));
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});

test('worktreeChanges adds uncommitted edits and every line of untracked files; changedFiles lists deletions too', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'git-diff-'));
  const git = (...args) => execFileSync('git', ['-c', 'user.name=t', '-c', 'user.email=t@t', ...args], { cwd: root, encoding: 'utf8' });
  const write = (file, text) => {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true });
    fs.writeFileSync(path.join(root, file), text);
  };
  try {
    git('init', '-q');
    write('.gitignore', 'build/\n');
    write('console/lib/a.ts', 'a\nb\n');
    write('console/lib/gone.ts', 'x\n');
    git('add', '.');
    git('commit', '-q', '-m', 'base');
    const base = git('rev-parse', 'HEAD').trim();
    write('console/lib/a.ts', 'a\nB\n');
    write('console/lib/new.ts', 'n\nm');
    write('console/build/out.js', 'ignored\n');
    fs.rmSync(path.join(root, 'console/lib/gone.ts'));

    const changed = worktreeChanges(base, ['.'], path.join(root, 'console'));
    assert.deepEqual(changed, new Map([['console/lib/a.ts', new Set([2])], ['console/lib/new.ts', new Set([1, 2])]]));
    assert.deepEqual(changedFiles(base, root), ['console/lib/a.ts', 'console/lib/gone.ts', 'console/lib/new.ts']);
  } finally {
    fs.rmSync(root, { recursive: true, force: true });
  }
});
