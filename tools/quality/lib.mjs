/**
 * Shared output contract of the quality reports (ADR-0082): every check writes
 * `<check>-summary.txt`, `<check>-report.txt` and `gl-code-quality-<check>.json` into its own
 * directory, and never exits non-zero because of findings.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const sha1 = (value) => crypto.createHash('sha1').update(value).digest('hex');

/** `findings` → Code Quality (Code Climate) entries; a fingerprint survives the finding moving within its file. */
export function codeQuality(check, findings) {
  const seen = new Map();
  return findings.map((f) => {
    const key = `${f.path}|${f.stableKey ?? f.message}`;
    const occurrence = seen.get(key) ?? 0;
    seen.set(key, occurrence + 1);
    return {
      description: f.message,
      check_name: check,
      // The check name keeps two checks' findings at one spot apart in the merged report.
      fingerprint: sha1(`${check}|${key}|${occurrence}`),
      severity: f.severity ?? 'minor',
      location: { path: f.path, lines: { begin: f.line } },
    };
  });
}

export function formatReport(title, findings) {
  const lines = [`${title}: ${findings.length} finding(s)`];
  for (const f of findings) lines.push(`${f.path}:${f.line}  ${f.message}`);
  return `${lines.join('\n')}\n`;
}

export function formatSummary(count, extra = {}) {
  const lines = [`findings=${count}`, ...Object.entries(extra).map(([k, v]) => `${k}=${v}`)];
  return `${lines.join('\n')}\n`;
}

/** Writes the three files of `check` into `dir` and returns the report text. */
export function writeCheck(dir, check, title, findings, extra = {}) {
  fs.mkdirSync(dir, { recursive: true });
  const report = formatReport(title, findings);
  fs.writeFileSync(path.join(dir, `${check}-report.txt`), report);
  fs.writeFileSync(path.join(dir, `${check}-summary.txt`), formatSummary(findings.length, extra));
  fs.writeFileSync(path.join(dir, `gl-code-quality-${check}.json`), `${JSON.stringify(codeQuality(check, findings), null, 2)}\n`);
  return report;
}

export function parseSummary(text) {
  return Object.fromEntries(
    text
      .split('\n')
      .filter((line) => line.includes('='))
      .map((line) => [line.slice(0, line.indexOf('=')), line.slice(line.indexOf('=') + 1)]),
  );
}

/** Repo-relative POSIX path of `file` (absolute or relative to `root`). */
export const repoRelative = (root, file) => path.relative(root, path.resolve(root, file)).split(path.sep).join('/');

/** Runs `main` when the module is the entry point; a thrown error is a real failure (exit 1). */
export function runMain(meta, main) {
  if (!process.argv[1] || meta.url !== pathToFileURL(path.resolve(process.argv[1])).href) return;
  try {
    const output = main(process.argv.slice(2));
    if (output) process.stdout.write(output);
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
}
