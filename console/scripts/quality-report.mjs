/**
 * Shared output of the console's report-only quality checks (ADR-0082). Each check turns its tool's
 * output into findings `{path, line, message, severity}` — `path` repo-relative, `message` free of
 * line numbers, `severity` `minor` or `major` — and writes them to `reports/quality/<check>/` as
 * `<check>-summary.txt`, `<check>-report.txt` and `gl-code-quality-<check>.json`, the files the CI
 * quality gate reads. Findings never fail a check; only a tool error does.
 */
import { spawnSync } from 'node:child_process';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';

export const CONSOLE_DIR = path.join(import.meta.dirname, '..');
export const REPORTS_DIR = path.join(CONSOLE_DIR, 'reports', 'quality');

const SEVERITY_RANK = { major: 0, minor: 1 };

/** The repo-relative posix path of a path relative to (or absolute under) the console directory. */
export function repoPath(consolePath, consoleDir = CONSOLE_DIR) {
  const relative = path.isAbsolute(consolePath) ? path.relative(consoleDir, consolePath) : consolePath;
  return `console/${relative.split(path.sep).join('/')}`;
}

/** Major findings first, then by path and line. */
export function sortFindings(findings) {
  return [...findings].sort(
    (a, b) => SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity] || a.path.localeCompare(b.path) || a.line - b.line,
  );
}

export function formatReport(title, findings, detail = '') {
  const header = `${title}: ${findings.length} finding(s)${detail ? ` ${detail}` : ''}`;
  return `${[header, ...sortFindings(findings).map((f) => `${f.path}:${f.line}  ${f.message}`)].join('\n')}\n`;
}

/** `findings=<n>` then one `key=value` line per `extra` entry, in insertion order. */
export function formatSummary(count, extra = {}) {
  return `${[`findings=${count}`, ...Object.entries(extra).map(([key, value]) => `${key}=${value}`)].join('\n')}\n`;
}

function sha1(text) {
  return crypto.createHash('sha1').update(text).digest('hex');
}

/**
 * Code Quality (Code Climate) entries. The fingerprint hashes `path|key|n`, where `key` is the finding's
 * `stableKey` (for a message that holds a changing number) or else its message, and `n` tells apart
 * same-file findings with the same key by their line order, so it survives unrelated edits.
 */
export function codeQuality(check, findings) {
  const seen = new Map();
  return [...findings]
    .sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line)
    .map((f) => {
      const key = `${f.path}|${f.stableKey ?? f.message}`;
      const n = seen.get(key) ?? 0;
      seen.set(key, n + 1);
      return {
        description: f.message,
        check_name: check,
        fingerprint: sha1(`${key}|${n}`),
        severity: f.severity,
        location: { path: f.path, lines: { begin: f.line } },
      };
    });
}

/** Writes the three report files of `check` under `reportsDir` and returns the report text. */
export function writeReports(check, { title, findings, detail = '', extra = {} }, reportsDir = REPORTS_DIR) {
  const dir = path.join(reportsDir, check);
  const report = formatReport(title, findings, detail);
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, `${check}-report.txt`), report);
  fs.writeFileSync(path.join(dir, `${check}-summary.txt`), formatSummary(findings.length, extra));
  fs.writeFileSync(path.join(dir, `gl-code-quality-${check}.json`), `${JSON.stringify(codeQuality(check, findings), null, 2)}\n`);
  return report;
}

/** Prints the report's header and its first `limit` findings, pointing at the file for the rest. */
export function printReport(check, report, limit = 20, reportsDir = REPORTS_DIR) {
  const lines = report.trimEnd().split('\n');
  console.log(lines.slice(0, 1 + limit).join('\n'));
  if (lines.length > 1 + limit) {
    console.log(`… ${lines.length - 1 - limit} more in ${path.join(reportsDir, check, `${check}-report.txt`)}`);
  }
}

/**
 * Runs `node_modules/.bin/<bin>` in the console directory and returns its stdout (stderr passes
 * through). Throws unless it exits 0: a check's tool must not fail on findings.
 */
export function runTool(bin, args, { capture = true } = {}) {
  const result = spawnSync(path.join(CONSOLE_DIR, 'node_modules', '.bin', bin), args, {
    cwd: CONSOLE_DIR,
    encoding: 'utf8',
    maxBuffer: 512 * 1024 * 1024,
    stdio: ['ignore', capture ? 'pipe' : 'inherit', 'inherit'],
  });
  if (result.error) throw result.error;
  if (result.status !== 0) throw new Error(`${bin} ${args.join(' ')} exited with status ${result.status ?? result.signal}`);
  return result.stdout;
}
