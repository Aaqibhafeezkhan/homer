/**
 * Backend mutation testing (PIT pilot, ADR-0082): turns PIT's `mutations.xml` into the shared
 * report files of check `mutation-backend`, one finding per survived or uncovered mutant.
 * Mutants that only remove a call the Kotlin compiler adds are ignored: outside the score, no finding.
 * Accepted exceptions (mutation-exceptions.mjs) leave the score and the findings, and are listed apart.
 * Usage (repository root): node tools/quality/pit.mjs [mutations.xml] [out dir]
 *
 * Not a quality-gate check yet: the nightly job only shows the report in its job summary
 * (tools/quality/step-summary.mjs).
 */
import fs from 'node:fs';
import path from 'node:path';
import { mutationScore, runMain, writeCheck } from './lib.mjs';
import { BACKEND_EXCEPTIONS, applyExceptions, loadExceptions, staleFindings, writeAccepted } from './mutation-exceptions.mjs';

export const CHECK = 'mutation-backend';
export const REPORT_DIR = 'app/app/build/reports/pitest';
export const SOURCE_ROOT = 'app/app/src/main/kotlin';
const FINDING_STATUSES = { SURVIVED: 'survived', NO_COVERAGE: 'no coverage' };
// Null checks, coroutine result checks and index-overflow guards the Kotlin compiler emits: removing
// one changes nothing a test can observe. Filtered here, not by avoidCallsTo: see ADR-0082.
const COMPILER_CALL = /^removed call to kotlin\/(jvm\/internal\/Intrinsics|ResultKt|collections\/CollectionsKt)::(check|throw)/;
const ENTITIES = { lt: '<', gt: '>', amp: '&', apos: "'", quot: '"' };

/** XML text with its predefined and numeric character references resolved. */
export function decodeXml(text) {
  return text.replace(/&(#x[0-9a-fA-F]+|#\d+|lt|gt|amp|apos|quot);/g, (reference, ref) => {
    if (ref[0] !== '#') return ENTITIES[ref];
    const codePoint = ref[1] === 'x' ? parseInt(ref.slice(2), 16) : Number(ref.slice(1));
    return codePoint <= 0x10ffff ? String.fromCodePoint(codePoint) : reference;
  });
}

const element = (body, name) => {
  const match = new RegExp(`<${name}>([\\s\\S]*?)</${name}>`).exec(body);
  return match ? decodeXml(match[1]) : '';
};

/** The `<mutation>` elements of a PIT XML report: status, source path, line, method and description. */
export function parseMutations(xml) {
  return [...xml.matchAll(/<mutation\b([^>]*)>([\s\S]*?)<\/mutation>/g)].map(([, attributes, body]) => {
    const mutatedClass = element(body, 'mutatedClass');
    const packageDir = mutatedClass.includes('.') ? mutatedClass.slice(0, mutatedClass.lastIndexOf('.')).replaceAll('.', '/') : '';
    return {
      status: /\bstatus=['"]([A-Z_]+)['"]/.exec(attributes)?.[1] ?? 'UNKNOWN',
      path: [SOURCE_ROOT, packageDir, element(body, 'sourceFile')].filter(Boolean).join('/'),
      line: Number(element(body, 'lineNumber')) || 1,
      method: element(body, 'mutatedMethod'),
      description: element(body, 'description'),
    };
  });
}

// The report and the job summary read one line per finding, and PIT's XML writer keeps newlines.
const oneLine = (text) => text.replace(/\s+/g, ' ').trim();

/** One finding per survived or uncovered mutant, in file and line order. */
export function pitFindings(mutations) {
  return mutations
    .filter((m) => Object.hasOwn(FINDING_STATUSES, m.status))
    .map((m) => ({
      path: m.path,
      line: m.line,
      message: `${FINDING_STATUSES[m.status]}: ${oneLine(m.method)} → ${oneLine(m.description)}`,
      severity: 'minor',
    }))
    .sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line);
}

/**
 * Where PIT places a source in its report: the package's directory and the file name, which is
 * not the file's own path when its directory does not match its package. Null without a package.
 */
export function pitPath(file, source) {
  const pkg = /^\s*package\s+([\w.]+)/m.exec(source)?.[1];
  return pkg ? `${SOURCE_ROOT}/${pkg.replaceAll('.', '/')}/${path.posix.basename(file)}` : null;
}

const regularFile = (file) => {
  const stat = fs.lstatSync(file, { throwIfNoEntry: false });
  return stat?.isFile() ? file : null;
};

/**
 * A reader of the backend sources by PIT path: the file at that path, else (a directory that does not
 * match the package) the source declaring that package under that file name. Regular files only,
 * never through a link; null for a source it cannot find.
 */
export function sourceReader(root = SOURCE_ROOT) {
  let index = null;
  const build = () => {
    const found = new Map();
    const walk = (dir) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const file = path.join(dir, entry.name);
        if (entry.isDirectory()) walk(file);
        else if (entry.isFile() && entry.name.endsWith('.kt')) {
          const at = pitPath(`${root}/${entry.name}`, fs.readFileSync(file, 'utf8'));
          if (at) found.set(at.slice(SOURCE_ROOT.length + 1), file);
        }
      }
    };
    if (fs.existsSync(root)) walk(root);
    return found;
  };
  return (pit) => {
    const relative = pit.startsWith(`${root}/`) ? pit.slice(root.length + 1) : pit;
    const direct = regularFile(path.join(root, relative));
    const file = direct ?? (index ??= build()).get(relative);
    return file ? fs.readFileSync(file, 'utf8') : null;
  };
}

/**
 * The accepted-exception key of a mutant (mutation-exceptions.mjs): the PIT source path (relative to
 * the source root), method, description and the mutant's line text, trimmed with whitespace runs
 * collapsed; `<inline>` when the line is past the file's end (inlined Kotlin code) or the source is
 * unreadable. No line number, so the key is the same when the mutant moves with its line.
 */
export function mutationKey(m, read) {
  const lines = read(m.path)?.split('\n');
  const text = lines && m.line <= lines.length - (lines.at(-1) === '' ? 1 : 0) ? oneLine(lines[m.line - 1]) : '<inline>';
  return `${m.path.startsWith(`${SOURCE_ROOT}/`) ? m.path.slice(SOURCE_ROOT.length + 1) : m.path}#${oneLine(m.method)}#${oneLine(m.description)}#${text}`;
}

/** The mutants with their `key`, built from their PIT path: do it before any rewrite of `path`. */
export const withKeys = (mutations, read) => mutations.map((m) => ({ ...m, key: mutationKey(m, read) }));

/** Whether the mutant only removes a call the Kotlin compiler added. */
export const isCompilerCallMutant = (m) => COMPILER_CALL.test(m.description);

const SCORED = { KILLED: 'killed', SURVIVED: 'survived', NO_COVERAGE: 'noCoverage', TIMED_OUT: 'timeout' };

/**
 * Mutant counts in the console report's terms (console/scripts/mutation.mjs) and the shared
 * score (`mutationScore`). Every other status (memory and run errors, non-viable, not started,
 * one PIT adds later) is an error, outside the score, so the counts always add up to the total.
 */
export function pitStats(mutations) {
  const stats = { killed: 0, survived: 0, noCoverage: 0, timeout: 0, errors: 0 };
  for (const m of mutations) stats[Object.hasOwn(SCORED, m.status) ? SCORED[m.status] : 'errors'] += 1;
  return { score: mutationScore(stats), ...stats };
}

/**
 * Writes the check's report files for `all` mutants: compiler-call mutants left out, then the undetected
 * mutants `exceptions` accept (out of the score and the findings, listed in `<check>-accepted.txt`).
 * A `full` run also reports the exceptions that match no mutant; returns the report text.
 */
export function writeReport(outDir, title, all, { exceptions = {}, full = false } = {}) {
  const mutations = all.filter((m) => !isCompilerCallMutant(m));
  const undetected = mutations.filter((m) => Object.hasOwn(FINDING_STATUSES, m.status));
  const { kept, accepted, stale } = applyExceptions(undetected, (m) => m.key, exceptions, { full });
  const scored = [...mutations.filter((m) => !undetected.includes(m)), ...kept];
  const stats = { ...pitStats(scored), ignored: all.length - mutations.length, accepted: accepted.length };
  const score = stats.score === 'n/a' ? 'no mutants' : `score ${stats.score}%`;
  writeAccepted(outDir, CHECK, accepted);
  return writeCheck(outDir, CHECK, `${title} (${score})`, [...pitFindings(kept), ...staleFindings(stale, BACKEND_EXCEPTIONS)], stats);
}

/** The `<mutation>` elements of PIT's XML report `xmlFile`, with their exception keys. */
export function readMutations(xmlFile, read = sourceReader()) {
  if (!fs.existsSync(xmlFile)) throw new Error(`no ${xmlFile}: did :app:pitest run?`);
  return withKeys(parseMutations(fs.readFileSync(xmlFile, 'utf8')), read);
}

export function main([xmlFile = `${REPORT_DIR}/mutations.xml`, outDir = REPORT_DIR]) {
  return writeReport(outDir, 'Backend mutation testing', readMutations(xmlFile), { exceptions: loadExceptions(BACKEND_EXCEPTIONS), full: true });
}

runMain(import.meta, main);
