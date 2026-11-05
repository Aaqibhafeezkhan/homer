#!/usr/bin/env node
/**
 * Mutation report (check `mutation-console`, ADR-0082): runs Stryker with `stryker.config.mjs` and
 * turns its JSON report into findings — every survived or uncovered mutant.
 * `--changed` mutates only the lines a merge request adds or changes in scored files — the diff
 * against `MUTATE_BASE` (a commit), or with `MUTATE_WORKTREE=1` the working tree's, uncommitted and
 * untracked files included (the diff mode, tools/quality/changed.mjs) — and skips Stryker when there
 * are none. Whole files would make
 * an MR's run scale with the size of every file it touches (hours for a dozen components). Both
 * modes ignore static mutants, each of which reruns the whole suite.
 * Accepted exceptions (tools/quality/mutation-exceptions/console.json) leave the score and the findings
 * and are listed in `mutation-console-accepted.txt`; a full run also reports the entries matching no mutant.
 * Writes `reports/quality/mutation-console/`.
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { pathToFileURL } from 'node:url';

import { changedLines, gitDiff, worktreeChanges } from '../../tools/quality/git-diff.mjs';
import { mutationScore } from '../../tools/quality/lib.mjs';
import { CONSOLE_EXCEPTIONS, applyExceptions, countFindings, loadExceptions, staleFindings, writeAccepted } from '../../tools/quality/mutation-exceptions.mjs';
import { isScoredPath } from './crap.mjs';
import { CONSOLE_DIR, REPORTS_DIR, printReport, repoPath, runTool, writeReports } from './quality-report.mjs';

const CHECK = 'mutation-console';
const TITLE = 'Mutation testing';
const STRYKER_REPORT = path.join(REPORTS_DIR, CHECK, 'mutation.json');
const FINDING_STATUSES = { Survived: 'survived', NoCoverage: 'no coverage' };
const REPLACEMENT_CHARS = 60;

// Stryker reads `mutate` entries as globs and refuses a range on a path with glob characters, which
// every Next dynamic route (`[name]`) has; such a file is mutated whole, its path escaped.
const GLOB_CHARS = /[[\]{}*?!]/;

/**
 * Stryker `mutate` entries for the changed lines (`changedLines` of a `git diff -U0`) in scored
 * console files that still exist: `<console-relative file>:<start>-<end>` ranges, in file and line order.
 */
export function changedRanges(changed, exists) {
  const entries = [];
  const files = [...changed]
    .filter(([file]) => file.startsWith('console/'))
    .map(([file, lines]) => [file.slice('console/'.length), [...lines].sort((a, b) => a - b)])
    .filter(([file, lines]) => lines.length > 0 && isScoredPath(file) && exists(file))
    .sort(([a], [b]) => a.localeCompare(b));
  for (const [file, lines] of files) {
    if (GLOB_CHARS.test(file)) {
      entries.push(file.replace(new RegExp(GLOB_CHARS, 'g'), '\\$&'));
      continue;
    }
    let start = lines[0];
    for (let i = 1; i <= lines.length; i++) {
      if (lines[i] === lines[i - 1] + 1) continue;
      entries.push(`${file}:${start}-${lines[i - 1]}`);
      start = lines[i];
    }
  }
  return entries;
}

/**
 * The report restricted to the mutants inside `ranges` (`changedRanges` entries). Stryker runs
 * incrementally, so its report also carries every other file's mutants, reused from the cached
 * incremental file of the last full run; only the changed lines are this change's findings.
 */
export function withinRanges(report, ranges, consoleDir = CONSOLE_DIR) {
  const wanted = new Map();
  for (const entry of ranges) {
    const match = /^(.*):(\d+)-(\d+)$/.exec(entry);
    const file = (match ? match[1] : entry).replace(/\\(.)/g, '$1');
    const spans = wanted.get(file) ?? [];
    spans.push(match ? [Number(match[2]), Number(match[3])] : [1, Infinity]);
    wanted.set(file, spans);
  }
  const files = {};
  for (const [file, data] of Object.entries(report.files)) {
    const relative = (path.isAbsolute(file) ? path.relative(consoleDir, file) : file).split(path.sep).join('/');
    const spans = wanted.get(relative);
    if (!spans) continue;
    const inside = data.mutants.filter(({ location }) => spans.some(([from, to]) => location.start.line >= from && location.start.line <= to));
    files[file] = { ...data, mutants: inside };
  }
  return { ...report, files };
}

const collapse = (text) => text.replace(/\s+/g, ' ').trim();

// The text of `location` (1-based line and column, start inclusive, end exclusive) in `source`.
function sourceText(source, { start, end }) {
  const lines = source.split('\n');
  if (start.line === end.line) return (lines[start.line - 1] ?? '').slice(start.column - 1, end.column - 1);
  return [(lines[start.line - 1] ?? '').slice(start.column - 1), ...lines.slice(start.line, end.line - 1), (lines[end.line - 1] ?? '').slice(0, end.column - 1)].join('\n');
}

/**
 * The accepted-exception key of a mutant (tools/quality/mutation-exceptions.mjs):
 * `<file relative to console/>#<mutator>#<original text>→<replacement>#<the mutated line's text>`,
 * whitespace runs collapsed to one space, the line trimmed; `<unknown>` without that line. The line text
 * keeps an entry from matching the same-text mutants elsewhere in the file. No line number, so the
 * key is the same when the mutant moves with its line.
 */
export function mutantKey(file, mutant, source, consoleDir = CONSOLE_DIR) {
  const relative = (path.isAbsolute(file) ? path.relative(consoleDir, file) : file).split(path.sep).join('/');
  const line = (source ?? '').split('\n')[mutant.location.start.line - 1];
  const lineText = line === undefined ? '<unknown>' : collapse(line);
  return `${relative}#${mutant.mutatorName}#${collapse(sourceText(source ?? '', mutant.location))}→${collapse(mutant.replacement ?? '')}#${lineText}`;
}

/**
 * Applies the exceptions to the undetected (survived or uncovered) mutants of `report`: returns the
 * report without the accepted ones, them as `accepted`, and (only for a `full` run) the stale keys and
 * the miscounted entries (`applyExceptions`).
 */
export function acceptExceptions(report, exceptions, { full }, consoleDir = CONSOLE_DIR) {
  const undetected = mutants(report).filter(({ mutant }) => mutant.status in FINDING_STATUSES);
  const keyOf = ({ file, mutant }) => mutantKey(file, mutant, report.files[file].source, consoleDir);
  const { accepted, stale, miscounted } = applyExceptions(undetected, keyOf, exceptions, { full });
  const dropped = new Set(accepted.map(({ mutant }) => mutant.mutant));
  const files = Object.fromEntries(Object.entries(report.files).map(([file, data]) => [file, { ...data, mutants: data.mutants.filter((m) => !dropped.has(m)) }]));
  return { report: { ...report, files }, accepted, stale, miscounted };
}

function shortReplacement(replacement) {
  const text = (replacement ?? '').replace(/\s+/g, ' ').trim();
  return text.length > REPLACEMENT_CHARS ? `${text.slice(0, REPLACEMENT_CHARS - 1)}…` : text;
}

function mutants(report) {
  return Object.entries(report.files).flatMap(([file, { mutants: fileMutants }]) => fileMutants.map((mutant) => ({ file, mutant })));
}

/** One finding per survived or uncovered mutant of a mutation-testing-report-schema JSON report. */
export function mutantFindings(report) {
  return mutants(report)
    .filter(({ mutant }) => mutant.status in FINDING_STATUSES)
    .map(({ file, mutant }) => ({
      path: repoPath(file),
      line: mutant.location.start.line,
      message: `${FINDING_STATUSES[mutant.status]}: ${mutant.mutatorName} → ${shortReplacement(mutant.replacement)}`,
      severity: 'minor',
    }));
}

/** Mutant counts per status and Stryker's mutation score: detected / (detected + undetected). */
export function mutationStats(report) {
  const count = (status) => mutants(report).filter(({ mutant }) => mutant.status === status).length;
  const stats = {
    killed: count('Killed'),
    survived: count('Survived'),
    noCoverage: count('NoCoverage'),
    timeout: count('Timeout'),
    errors: count('CompileError') + count('RuntimeError'),
    ignored: count('Ignored'),
  };
  return { score: mutationScore(stats), ...stats };
}

function changedSince(base) {
  if (!base) return new Map();
  return process.env.MUTATE_WORKTREE === '1' ? worktreeChanges(base, ['.'], CONSOLE_DIR) : changedLines(gitDiff(base, ['.'], CONSOLE_DIR));
}

function main() {
  const changed = process.argv.includes('--changed');
  let ranges = [];
  if (changed) {
    const base = process.env.MUTATE_BASE;
    ranges = changedRanges(changedSince(base), (file) => fs.existsSync(path.join(CONSOLE_DIR, file)));
    if (ranges.length === 0) {
      const reason = base ? 'no changed source lines' : 'no MUTATE_BASE';
      const report = writeReports(CHECK, { title: TITLE, findings: [], detail: `(skipped: ${reason})`, extra: { skipped: reason } });
      printReport(CHECK, report);
      return;
    }
  }
  fs.rmSync(STRYKER_REPORT, { force: true });
  runTool('stryker', ['run', '--ignoreStatic', ...(changed ? ['--mutate', ranges.join(',')] : [])], { capture: false });
  if (!fs.existsSync(STRYKER_REPORT)) throw new Error(`${STRYKER_REPORT} not found — Stryker wrote no JSON report.`);
  const fullReport = JSON.parse(fs.readFileSync(STRYKER_REPORT, 'utf8'));
  const strykerReport = changed ? withinRanges(fullReport, ranges) : fullReport;
  const exceptions = loadExceptions(path.join(CONSOLE_DIR, '..', CONSOLE_EXCEPTIONS));
  const { report: scored, accepted, stale, miscounted } = acceptExceptions(strykerReport, exceptions, { full: !changed });
  const stats = mutationStats(scored);
  writeAccepted(path.join(REPORTS_DIR, CHECK), CHECK, accepted);
  const report = writeReports(CHECK, {
    title: TITLE,
    findings: [...mutantFindings(scored), ...staleFindings(stale, CONSOLE_EXCEPTIONS), ...countFindings(miscounted, CONSOLE_EXCEPTIONS)],
    detail: `(score ${stats.score}%, ${changed ? `${ranges.length} changed range(s)` : 'full run'})`,
    extra: { ...stats, accepted: accepted.length },
  });
  printReport(CHECK, report);
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    main();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
