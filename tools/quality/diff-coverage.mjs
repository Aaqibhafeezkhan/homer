/**
 * diff-coverage: test coverage of the lines a merge request adds or changes (ADR-0082), from the
 * backend's Kover XML reports and the console's V8 `coverage-final.json`. One finding per changed
 * file whose changed, instrumented lines are less than THRESHOLD% covered.
 * Usage (repository root): node tools/quality/diff-coverage.mjs [base sha] [out dir]
 * The base defaults to CI_MERGE_REQUEST_DIFF_BASE_SHA; without one there is no diff to measure.
 */
import fs from 'node:fs';
import path from 'node:path';
import { changedLines, gitDiff } from './git-diff.mjs';
import { repoRelative, runMain, writeCheck } from './lib.mjs';

export const THRESHOLD = 80;
const KOTLIN_SOURCE = /^app\/([\w-]+)\/src\/main\/kotlin\/.+\.kt$/;
const NOT_INSTRUMENTED_MODULES = new Set(['buildSrc', 'architecture', 'e2e']);
const CONSOLE_SOURCE = /^console\/(?:(?:app|components|lib)\/.+\.tsx?|middleware\.ts)$/;
const CONSOLE_EXCLUDED = /\.test\.|\.d\.ts$|^console\/components\/ui\//;

export const isKotlinSource = (file) => KOTLIN_SOURCE.test(file) && !NOT_INSTRUMENTED_MODULES.has(KOTLIN_SOURCE.exec(file)[1]);
export const isConsoleSource = (file) => CONSOLE_SOURCE.test(file) && !CONSOLE_EXCLUDED.test(file);

/** Kover/JaCoCo XML → Map("<package path>/<file name>" → Map(line → covered?)). */
export function koverLines(xml) {
  const files = new Map();
  let pkg = '';
  let current = null;
  for (const [tag] of xml.matchAll(/<(?:package|sourcefile|line)\b[^>]*>/g)) {
    const name = /\bname="([^"]*)"/.exec(tag)?.[1];
    if (tag.startsWith('<package')) pkg = name;
    else if (tag.startsWith('<sourcefile')) {
      current = new Map();
      files.set(pkg ? `${pkg}/${name}` : name, current);
    } else if (current) {
      const nr = Number(/\bnr="(\d+)"/.exec(tag)[1]);
      const covered = Number(/\bci="(\d+)"/.exec(tag)?.[1] ?? 0) > 0;
      current.set(nr, covered);
    }
  }
  return files;
}

/** The Kover key of a Kotlin source: its `package` declaration plus its file name. */
export function koverKey(file, source) {
  const pkg = /^\s*package\s+([\w.]+)/m.exec(source)?.[1];
  return pkg ? `${pkg.replace(/\./g, '/')}/${path.basename(file)}` : path.basename(file);
}

/** istanbul file coverage → Map(line → covered?): a line is covered when every statement starting on it ran. */
export function statementLines(entry) {
  const lines = new Map();
  for (const [id, loc] of Object.entries(entry.statementMap)) {
    const line = loc.start.line;
    lines.set(line, (lines.get(line) ?? true) && entry.s[id] > 0);
  }
  return lines;
}

/** Per changed file: its changed instrumented lines and which of them are uncovered. */
export function measure(changed, coverageOf) {
  const files = [];
  for (const [file, lines] of changed) {
    const coverage = coverageOf(file);
    if (!coverage) continue;
    const instrumented = [...lines].filter((n) => coverage.has(n)).sort((a, b) => a - b);
    if (instrumented.length === 0) continue;
    const uncovered = instrumented.filter((n) => !coverage.get(n));
    files.push({ file, total: instrumented.length, uncovered });
  }
  return files;
}

const ranges = (lines) => {
  const out = [];
  for (const n of lines) {
    const last = out.at(-1);
    if (last && n === last[1] + 1) last[1] = n;
    else out.push([n, n]);
  }
  return out.map(([a, b]) => (a === b ? `${a}` : `${a}-${b}`)).join(', ');
};

export function toFindings(files) {
  return files
    .map((f) => ({ ...f, pct: (100 * (f.total - f.uncovered.length)) / f.total }))
    .filter((f) => f.pct < THRESHOLD)
    .sort((a, b) => a.pct - b.pct || a.file.localeCompare(b.file))
    .map((f) => ({
      path: f.file,
      line: f.uncovered[0],
      message: `${f.total - f.uncovered.length}/${f.total} changed lines covered (${f.pct.toFixed(1)}%), uncovered: ${ranges(f.uncovered)}`,
      stableKey: 'diff-coverage',
      severity: f.pct < THRESHOLD / 2 ? 'major' : 'minor',
    }));
}

/** File → its line coverage (`measure`'s `coverageOf`), read from the Kover and V8 reports under `root`. */
export function coverageLookup(root) {
  const kover = new Map();
  let consoleCoverage;
  return (file) => {
    if (isKotlinSource(file)) {
      const module = KOTLIN_SOURCE.exec(file)[1];
      if (!kover.has(module)) {
        const report = path.join(root, 'app', module, 'build', 'reports', 'kover', 'report.xml');
        if (!fs.existsSync(report)) throw new Error(`${file} changed but ${repoRelative(root, report)} is missing`);
        kover.set(module, koverLines(fs.readFileSync(report, 'utf8')));
      }
      return kover.get(module).get(koverKey(file, fs.readFileSync(path.join(root, file), 'utf8')));
    }
    if (isConsoleSource(file)) {
      if (!consoleCoverage) {
        const report = path.join(root, 'console', 'coverage', 'coverage-final.json');
        if (!fs.existsSync(report)) throw new Error(`${file} changed but console/coverage/coverage-final.json is missing`);
        const entries = Object.values(JSON.parse(fs.readFileSync(report, 'utf8')));
        consoleCoverage = new Map(entries.map((e) => [repoRelative(root, e.path), statementLines(e)]));
      }
      return consoleCoverage.get(file);
    }
    return undefined;
  };
}

export function main([base = process.env.CI_MERGE_REQUEST_DIFF_BASE_SHA, outDir = 'reports/quality/diff-coverage']) {
  const title = (extra) => `Diff coverage (${extra}, threshold ${THRESHOLD}%)`;
  if (!base) {
    return writeCheck(outDir, 'diff-coverage', title('no merge request'), [], { skipped: 'no merge-request base', threshold: THRESHOLD });
  }
  const root = process.cwd();
  const files = measure(changedLines(gitDiff(base, ['app', 'console'], root)), coverageLookup(root));
  const total = files.reduce((n, f) => n + f.total, 0);
  const covered = files.reduce((n, f) => n + f.total - f.uncovered.length, 0);
  const coverage = total === 0 ? 'n/a' : ((100 * covered) / total).toFixed(1);
  const extra = { coverage, lines: total, covered, threshold: THRESHOLD };
  const label = total === 0 ? 'no changed instrumented lines' : `${coverage}% of ${total} changed lines`;
  return writeCheck(outDir, 'diff-coverage', title(label), toFindings(files), extra).split('\n').slice(0, 21).join('\n') + '\n';
}

runMain(import.meta, main);
