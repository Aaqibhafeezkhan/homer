/**
 * kotlin-warnings: every Kotlin compiler warning in a Gradle build log (ADR-0082).
 * Usage: node tools/quality/kotlin-warnings.mjs <gradle.log> [out dir]
 *
 * Only compile tasks that ran print warnings. A task restored from the (remote) build cache or up to
 * date compiled the same sources as an earlier build, so its module is left out of the count and
 * named in the report: changed code always recompiles, so a new warning is never missed.
 */
import fs from 'node:fs';
import { repoRelative, runMain, writeCheck } from './lib.mjs';

const DIAGNOSTIC = /^([ew]): file:\/\/(\S+?):(\d+):\d+ (.+)$/;
const COMPILE_TASK = /^> Task (:[\w:-]*compile\w*Kotlin)(?: (UP-TO-DATE|FROM-CACHE))?\s*$/;
// Warnings that point at a likely defect rather than at style or an API in transition.
const MAJOR = [/might not be intended/, /is unused/, /always (true|false|null)/, /[Uu]nreachable code/, /will never/];

/**
 * One Kotlin compiler diagnostic line (`w:` warning or `e:` error, `file://<path>:<line>:<col> <text>`)
 * as `{kind, path, line, message}` with a repo-relative path, else null. The diff mode reads errors with it.
 */
export function diagnostic(line, root) {
  const match = DIAGNOSTIC.exec(line);
  if (!match) return null;
  const [, kind, file, lineNo, message] = match;
  return { kind, path: repoRelative(root, decodeURIComponent(file)), line: Number(lineNo), message: message.trim() };
}

export function parseLog(text, root) {
  const findings = new Map();
  const compiled = [];
  const notCompiled = [];
  for (const line of text.split('\n')) {
    const task = COMPILE_TASK.exec(line);
    if (task) (task[2] ? notCompiled : compiled).push(task[2] ? `${task[1]} ${task[2]}` : task[1]);
    const warning = diagnostic(line, root);
    if (warning?.kind !== 'w') continue;
    const { kind: _kind, ...location } = warning;
    const finding = { ...location, severity: MAJOR.some((re) => re.test(warning.message)) ? 'major' : 'minor' };
    findings.set(`${finding.path}:${finding.line}:${finding.message}`, finding);
  }
  const sorted = [...findings.values()].sort(
    (a, b) => (a.severity === b.severity ? 0 : a.severity === 'major' ? -1 : 1) || a.path.localeCompare(b.path) || a.line - b.line,
  );
  return { findings: sorted, compiled, notCompiled };
}

export function main([logFile, outDir = 'reports/quality/kotlin-warnings']) {
  if (!logFile) throw new Error('usage: kotlin-warnings.mjs <gradle.log> [out dir]');
  if (!fs.existsSync(logFile)) throw new Error(`${logFile} not found`);
  const { findings, compiled, notCompiled } = parseLog(fs.readFileSync(logFile, 'utf8'), process.cwd());
  if (compiled.length === 0 && notCompiled.length === 0) throw new Error(`${logFile} shows no Kotlin compile task`);
  const extra = { compiled: compiled.length, notCompiled: notCompiled.length };
  if (compiled.length === 0) extra.skipped = 'every compile task came from the build cache or was up to date';
  const title =
    notCompiled.length === 0
      ? 'Kotlin compiler warnings'
      : `Kotlin compiler warnings (not counted, unchanged since a cached build: ${notCompiled.join(', ')})`;
  return writeCheck(outDir, 'kotlin-warnings', title, findings, extra).split('\n').slice(0, 21).join('\n') + '\n';
}

runMain(import.meta, main);
