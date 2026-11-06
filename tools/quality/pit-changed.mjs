/**
 * Backend mutation testing of a change (check `mutation-backend`, ADR-0082): PIT mutates the
 * backend classes whose lines the change adds or modifies, and the findings and score cover only
 * the mutants on those lines, as the console's `mutation:changed` does. The change is
 * `MUTATE_BASE..HEAD`, or with `MUTATE_WORKTREE=1` the working tree's (the diff mode,
 * tools/quality/changed.mjs). Only `:app` is mutated; no changed code line there: a skipped report,
 * no Gradle run. The PIT run has a time budget, `MUTATE_BUDGET_SEC` (default 14 minutes, inside the
 * CI job's 20): a run over it is stopped and reported skipped, and the weekly full run covers those
 * classes, instead of the job being cancelled with no report.
 * Usage (repository root, JAVA_HOME on a JDK 25): MUTATE_BASE=<rev> node tools/quality/pit-changed.mjs
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { declaredNames } from './changed.mjs';
import { changedLines, gitDiff, worktreeChanges } from './git-diff.mjs';
import { runMain, writeCheck } from './lib.mjs';
import { BACKEND_EXCEPTIONS, loadExceptions } from './mutation-exceptions.mjs';
import { CHECK, REPORT_DIR, SOURCE_ROOT, pitPath, readMutations, writeReport } from './pit.mjs';

export { pitPath };

const TITLE = 'Backend mutation testing of the change';
const SOURCE = new RegExp(`^${SOURCE_ROOT}/.+\\.kt$`);
export const DEFAULT_BUDGET_SEC = 14 * 60;

/** The PIT run's budget in seconds: `MUTATE_BUDGET_SEC`, a positive whole number, else the default. */
export function budgetSeconds(value) {
  if (value === undefined || value === '') return DEFAULT_BUDGET_SEC;
  if (!/^[1-9]\d{0,5}$/.test(value)) throw new Error(`MUTATE_BUDGET_SEC must be a positive whole number of seconds, not '${value}'`);
  return Number(value);
}

/** A budget as the skip reason names it: whole minutes when it is one, else seconds. */
export const budgetText = (seconds) => (seconds % 60 === 0 ? `${seconds / 60}-minute` : `${seconds}-second`);

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

const quoteAt = (source, i) => (source.startsWith('"""', i) ? '"""' : source[i] === '"' || source[i] === "'" ? source[i] : null);

// One step inside a string or char literal; returns the index of the step's last character.
function stepInLiteral(source, i, stack) {
  const { quote } = stack.at(-1);
  if (source[i] === '\\' && quote !== '"""') return i + 1;
  if (quote !== "'" && source.startsWith('${', i)) {
    stack.push({ braces: 0 });
    return i + 1;
  }
  if (!source.startsWith(quote, i)) return i;
  stack.pop();
  // A raw string closes at the last three of a run of quotes: `""""` is a quote, then the end.
  let end = i + quote.length - 1;
  while (quote === '"""' && source[end + 1] === '"') end++;
  return end;
}

// One step in code or in a `${…}` template expression; returns the index of the step's last character.
function stepInCode(source, i, stack) {
  // A backticked name (`fun \`it's fine\`()`) may hold quotes and comment openers: skip it whole.
  if (source[i] === '`') {
    const close = source.indexOf('`', i + 1);
    if (close >= 0) return close;
    stack.push({ quote: '`' }); // never closed: leave the source unclean, so the fail-safe applies
    return source.length;
  }
  const quote = quoteAt(source, i);
  if (quote) {
    stack.push({ quote });
    return i + quote.length - 1;
  }
  const template = stack.at(-1);
  if (template && source[i] === '{') template.braces++;
  else if (template && source[i] === '}') {
    if (template.braces === 0) stack.pop();
    else template.braces--;
  }
  return i;
}

/**
 * The lines of a Kotlin source holding anything outside a comment. Lexes just enough for that:
 * block comments nest; a comment opener inside a string or char literal is text; a `${…}` template
 * is code again, its own literals included. A source this lexer cannot read to a clean end (an
 * unclosed literal or comment, which valid Kotlin never has) counts as code on every line.
 */
export function codeLineNumbers(source) {
  const code = new Set();
  const stack = [];
  let line = 1;
  let depth = 0;
  for (let i = 0; i < source.length; i++) {
    const c = source[i];
    if (c === '\n') {
      line++;
    } else if (depth > 0) {
      const marker = source.slice(i, i + 2);
      if (marker === '/*' || marker === '*/') [depth, i] = [depth + (marker === '/*' ? 1 : -1), i + 1];
    } else if (stack.at(-1)?.quote) {
      code.add(line);
      i = stepInLiteral(source, i, stack);
    } else if (source.startsWith('//', i)) {
      while (i + 1 < source.length && source[i + 1] !== '\n') i++;
    } else if (source.startsWith('/*', i)) {
      [depth, i] = [1, i + 1];
    } else if (!/\s/.test(c)) {
      code.add(line);
      i = stepInCode(source, i, stack);
    }
  }
  if (stack.length > 0 || depth > 0) return new Set(source.split('\n').map((_, n) => n + 1));
  return code;
}

/** The changed lines of `source` that hold code PIT can mutate: not comments, blank, package or import lines. */
export function codeLines(source, lines) {
  const code = codeLineNumbers(source);
  const text = source.split('\n');
  return new Set([...lines].filter((n) => code.has(n) && !/^\s*(?:package|import)\s/.test(text[n - 1] ?? '')));
}

/**
 * The changed backend sources: `{ globs, lines }`, `lines` mapping each source's PIT path to its
 * repository path and changed lines. `read` returns a file's text, or null for one to skip. A
 * source whose changed lines hold no code is left out, so a comment-only change runs no PIT; the
 * changed lines kept are all of them, since PIT places no mutant on a comment line anyway.
 */
export function changedSources(changed, read) {
  const globs = new Set();
  const lines = new Map();
  for (const [file, fileLines] of changed) {
    if (!SOURCE.test(file) || fileLines.size === 0) continue;
    const source = read(file);
    const at = source === null ? null : pitPath(file, source);
    if (!at || codeLines(source, fileLines).size === 0) continue;
    // PIT reports both under one path, so a line could not be told apart: refuse rather than drop one.
    if (lines.has(at)) throw new Error(`${lines.get(at).path} and ${file} share PIT source path ${at}: one package, one file name`);
    classGlobs(file, source).forEach((g) => globs.add(g));
    lines.set(at, { path: file, lines: fileLines });
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

/** A branch decides what a source path holds: read regular files only, never through a link; null otherwise. */
export const readSource = (file) => {
  const stat = fs.lstatSync(file, { throwIfNoEntry: false });
  return stat?.isFile() ? fs.readFileSync(file, 'utf8') : null;
};

function changedSince(base) {
  return process.env.MUTATE_WORKTREE === '1' ? worktreeChanges(base, [SOURCE_ROOT]) : changedLines(gitDiff(base, [SOURCE_ROOT]));
}

const skip = (reason) => writeCheck(REPORT_DIR, CHECK, `${TITLE} (skipped: ${reason})`, [], { skipped: reason });

export function main() {
  const exceptions = loadExceptions(BACKEND_EXCEPTIONS); // a bad entry fails before the PIT run, not after it
  const budget = budgetSeconds(process.env.MUTATE_BUDGET_SEC);
  const base = process.env.MUTATE_BASE;
  const { globs, lines } = changedSources(base ? changedSince(base) : new Map(), readSource);
  if (globs.length === 0) return skip(base ? 'no changed :app code lines' : 'no MUTATE_BASE');
  const xml = path.join(REPORT_DIR, 'mutations.xml');
  fs.rmSync(xml, { force: true });
  const gradlew = path.join('app', 'gradlew');
  const gradle = spawnSync(gradlew, ['-p', 'app', '--console=plain', ':app:pitest', `-PpitestTargetClasses=${globs.join(',')}`], {
    stdio: 'inherit',
    timeout: budget * 1000,
  });
  if (gradle.error?.code === 'ETIMEDOUT') {
    // The timeout ends only the client: the daemon would go on running PIT and its minions.
    spawnSync(gradlew, ['-p', 'app', '--stop'], { stdio: 'inherit', timeout: 60_000 });
    console.error(`:app:pitest ran over its ${budget} s budget (MUTATE_BUDGET_SEC); the weekly full run covers these classes`);
    return skip(`over the ${budgetText(budget)} budget`);
  }
  if (gradle.status !== 0) throw new Error(`:app:pitest exited ${gradle.status ?? gradle.error?.message}`);
  return writeReport(REPORT_DIR, TITLE, withinChanged(readMutations(xml), lines), { exceptions, full: false });
}

runMain(import.meta, () => main());
