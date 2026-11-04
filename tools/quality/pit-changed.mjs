/**
 * Backend mutation testing of a change (check `mutation-backend`, ADR-0082): PIT mutates the
 * backend classes whose lines the change adds or modifies, and the findings and score cover only
 * the mutants on those lines, as the console's `mutation:changed` does. The change is
 * `MUTATE_BASE..HEAD`, or with `MUTATE_WORKTREE=1` the working tree's (the diff mode,
 * tools/quality/changed.mjs). No changed backend source line: a skipped report, no Gradle run.
 * Usage (repository root, JAVA_HOME on a JDK 25): MUTATE_BASE=<rev> node tools/quality/pit-changed.mjs
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { declaredNames } from './changed.mjs';
import { changedLines, gitDiff, worktreeChanges } from './git-diff.mjs';
import { runMain, writeCheck } from './lib.mjs';
import { CHECK, REPORT_DIR, SOURCE_ROOT, readMutations, writeReport } from './pit.mjs';

const TITLE = 'Backend mutation testing of the change';
const SOURCE = new RegExp(`^${SOURCE_ROOT}/.+\\.kt$`);

/**
 * Where PIT places a source in its report: the package's directory and the file name, which is
 * not the file's own path when its directory does not match its package. Null without a package.
 */
export function pitPath(file, source) {
  const pkg = /^\s*package\s+([\w.]+)/m.exec(source)?.[1];
  return pkg ? `${SOURCE_ROOT}/${pkg.replaceAll('.', '/')}/${path.posix.basename(file)}` : null;
}

/**
 * PIT target-class globs for a Kotlin source: its file class (`<File>Kt`, top-level functions) and
 * each top-level declaration, with their nested and generated classes (`$…`). Names are \w+ by
 * construction, so no glob can reach past the source's own package.
 */
export function classGlobs(file, source) {
  const pkg = /^\s*package\s+([\w.]+)/m.exec(source)?.[1];
  if (!pkg) return [];
  const names = [`${path.posix.basename(file, '.kt').replace(/\W/g, '')}Kt`, ...declaredNames(source)];
  return [...new Set(names)].filter(Boolean).flatMap((name) => [`${pkg}.${name}`, `${pkg}.${name}$*`]);
}

// A comment, blank, package or import line holds no code PIT can mutate.
const NO_CODE = /^\s*(?:$|\/\/|\/\*|\*|package\s|import\s)/;

/** The changed lines of `source` that may hold code. */
export function codeLines(source, lines) {
  const text = source.split('\n');
  return new Set([...lines].filter((n) => !NO_CODE.test(text[n - 1] ?? '')));
}

/**
 * The changed backend sources: `{ globs, lines }`, `lines` mapping each source's PIT path to its
 * repository path and changed code lines. `read` returns a file's text, or null for one to skip. A
 * source whose changed lines are all comments is left out, so a comment-only change runs no PIT.
 */
export function changedSources(changed, read) {
  const globs = new Set();
  const lines = new Map();
  for (const [file, fileLines] of changed) {
    if (!SOURCE.test(file) || fileLines.size === 0) continue;
    const source = read(file);
    const at = source === null ? null : pitPath(file, source);
    const code = at ? codeLines(source, fileLines) : new Set();
    if (code.size === 0) continue;
    classGlobs(file, source).forEach((g) => globs.add(g));
    lines.set(at, { path: file, lines: code });
  }
  return { globs: [...globs].sort(), lines };
}

/** The mutants on changed lines, with their source's repository path. */
export function withinChanged(mutations, lines) {
  return mutations.flatMap((m) => {
    const source = lines.get(m.path);
    return source?.lines.has(m.line) ? [{ ...m, path: source.path }] : [];
  });
}

// A branch decides what a source path holds: read regular files only, never through a link.
const readSource = (file) => {
  const stat = fs.lstatSync(file, { throwIfNoEntry: false });
  return stat?.isFile() ? fs.readFileSync(file, 'utf8') : null;
};

function changedSince(base) {
  return process.env.MUTATE_WORKTREE === '1' ? worktreeChanges(base, [SOURCE_ROOT]) : changedLines(gitDiff(base, [SOURCE_ROOT]));
}

export function main() {
  const base = process.env.MUTATE_BASE;
  const { globs, lines } = changedSources(base ? changedSince(base) : new Map(), readSource);
  if (globs.length === 0) {
    const reason = base ? 'no changed backend code lines' : 'no MUTATE_BASE';
    return writeCheck(REPORT_DIR, CHECK, `${TITLE} (skipped: ${reason})`, [], { skipped: reason });
  }
  const xml = path.join(REPORT_DIR, 'mutations.xml');
  fs.rmSync(xml, { force: true });
  const gradle = spawnSync(path.join('app', 'gradlew'), ['-p', 'app', '--console=plain', ':app:pitest', `-PpitestTargetClasses=${globs.join(',')}`], { stdio: 'inherit' });
  if (gradle.status !== 0) throw new Error(`:app:pitest exited ${gradle.status ?? gradle.error?.message}`);
  return writeReport(REPORT_DIR, TITLE, withinChanged(readMutations(xml), lines));
}

runMain(import.meta, () => main());
