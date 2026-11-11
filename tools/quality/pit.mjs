/**
 * Backend mutation testing (check `mutation-backend`, ADR-0082): turns PIT's `mutations.xml` into the
 * shared report files, one finding per survived or uncovered mutant. The report of both backend runs:
 * a pull request's changed lines (tools/quality/pit-changed.mjs) and the weekly full run, sharded per
 * package (tools/quality/pit-full.mjs).
 * Mutants that only remove a call the Kotlin compiler adds, and null-return mutants on a function or
 * lambda that returns Unit, are ignored: outside the score, no finding.
 * Accepted exceptions (mutation-exceptions.mjs) leave the score and the findings, and are listed apart.
 * Usage (repository root): node tools/quality/pit.mjs [mutations.xml] [out dir]
 */
import fs from 'node:fs';
import path from 'node:path';
import { mutationScore, runMain, writeCheck } from './lib.mjs';
import { BACKEND_EXCEPTIONS, applyExceptions, countFindings, loadExceptions, staleFindings, writeAccepted } from './mutation-exceptions.mjs';

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

/** The `<mutation>` elements of a PIT XML report: status, source path, line, method, descriptor and description. */
export function parseMutations(xml) {
  return [...xml.matchAll(/<mutation\b([^>]*)>([\s\S]*?)<\/mutation>/g)].map(([, attributes, body]) => {
    const mutatedClass = element(body, 'mutatedClass');
    const packageDir = mutatedClass.includes('.') ? mutatedClass.slice(0, mutatedClass.lastIndexOf('.')).replaceAll('.', '/') : '';
    return {
      status: /\bstatus=['"]([A-Z_]+)['"]/.exec(attributes)?.[1] ?? 'UNKNOWN',
      path: [SOURCE_ROOT, packageDir, element(body, 'sourceFile')].filter(Boolean).join('/'),
      line: Number(element(body, 'lineNumber')) || 1,
      method: element(body, 'mutatedMethod'),
      descriptor: element(body, 'methodDescription'),
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
 * never through a link; null for a source it cannot find. Each path is read once: a full run keys
 * thousands of mutants.
 */
export function sourceReader(root = SOURCE_ROOT) {
  let index = null;
  const cache = new Map();
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
  const read = (relative) => {
    const file = regularFile(path.join(root, relative)) ?? (index ??= build()).get(relative);
    return file ? fs.readFileSync(file, 'utf8') : null;
  };
  const reader = (pit) => {
    const relative = pit.startsWith(`${root}/`) ? pit.slice(root.length + 1) : pit;
    if (!cache.has(relative)) cache.set(relative, read(relative));
    return cache.get(relative);
  };
  // The source's lines, split once per file: `mutationKey` asks for every mutant.
  const lineCache = new Map();
  reader.lines = (pit) => {
    if (!lineCache.has(pit)) lineCache.set(pit, reader(pit)?.split('\n') ?? null);
    return lineCache.get(pit);
  };
  return reader;
}

/** The lines of a mutant's source, through the reader's own cache when it has one; null if unreadable. */
const sourceLines = (m, read) => (read.lines ? read.lines(m.path) : read(m.path)?.split('\n')) ?? null;

/**
 * The accepted-exception key of a mutant (mutation-exceptions.mjs): the PIT source path (relative to
 * the source root), method, description and the mutant's line text, trimmed with whitespace runs
 * collapsed; `<inline>` when the line is past the file's end (inlined Kotlin code) or the source is
 * unreadable. No line number, so the key is the same when the mutant moves with its line.
 */
export function mutationKey(m, read) {
  const lines = sourceLines(m, read);
  const text = lines && m.line <= lines.length - (lines.at(-1) === '' ? 1 : 0) ? oneLine(lines[m.line - 1]) : '<inline>';
  return `${m.path.startsWith(`${SOURCE_ROOT}/`) ? m.path.slice(SOURCE_ROOT.length + 1) : m.path}#${oneLine(m.method)}#${oneLine(m.description)}#${text}`;
}

/**
 * The mutants with their `key` and their `unitReturn`, built from their PIT path: do it before any
 * rewrite of `path`, so the predicates that filter them need no reader.
 */
export const withKeys = (mutations, read) => mutations.map((m) => ({ ...m, key: mutationKey(m, read), unitReturn: returnsUnit(m, sourceLines(m, read)) }));

/** Whether the mutant only removes a call the Kotlin compiler added. */
export const isCompilerCallMutant = (m) => COMPILER_CALL.test(m.description);

// `replaced return value with null` mutants PIT places on the `return` of a function or lambda whose
// result nothing reads. Ignored only when the mutated function itself returns Unit, decided from the
// JVM descriptor and, for a suspend state machine, from the source declaration: never from the name.
const NULL_RETURN = /^replaced return value with null\b/;
// `)V` is a plain function or lambda returning Unit, `)Lkotlin/Unit;` its boxed form.
const UNIT_DESCRIPTOR = /\)(?:V|Lkotlin\/Unit;)$/;
// A suspend function compiles to a state machine that returns Object whatever it returns, so the
// descriptor says nothing: the declaration decides. A suspend lambda's `invokeSuspend` takes the
// lambda instance, not a Continuation, and has no declaration — it matches nothing here.
const SUSPEND_DESCRIPTOR = /Lkotlin\/coroutines\/Continuation;\)Ljava\/lang\/Object;$/;
const escapeRegExp = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** The index of the `)` closing the `(` at `open`, or -1 when the source does not close it. */
function closingParen(source, open) {
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === '(') depth++;
    else if (source[i] === ')' && --depth === 0) return i;
  }
  return -1;
}

/** Whether a declaration whose parameter list ends at `close` returns Unit. */
function unitAfter(source, close) {
  const rest = source.slice(close + 1).replace(/^\s+/, '');
  if (rest.startsWith('{') || /^where\b/.test(rest)) return true; // a block body, or a where clause before one
  if (!rest.startsWith(':')) return false; // `=` infers the type, and nothing else declares Unit
  return /^Unit(?![?<.\w])/.test(rest.slice(1).trimStart()); // `Unit?` is observable, `Unit` is not
}

/**
 * Whether the source declares `fun <name>(…)` returning Unit: a block body or a `: Unit` after the
 * parameter list. An inferred `=` body, another type, no matching declaration and an overload the
 * name cannot tell apart all mean not Unit.
 */
export function declaresUnitReturn(lines, name) {
  if (!lines || !name) return false;
  const source = lines.join('\n');
  const declarations = [...source.matchAll(new RegExp(`\\bfun\\s+${escapeRegExp(name)}\\s*\\(`, 'g'))];
  if (declarations.length !== 1) return false;
  const [declaration] = declarations;
  const close = closingParen(source, declaration.index + declaration[0].length - 1);
  return close >= 0 && unitAfter(source, close);
}

/**
 * Whether the mutated function's own result is Unit: its JVM descriptor returns void or a boxed
 * `Unit`, or it is a suspend function whose declaration the source gives as Unit.
 */
export function returnsUnit(m, lines) {
  const descriptor = m.descriptor ?? '';
  if (UNIT_DESCRIPTOR.test(descriptor)) return true;
  return SUSPEND_DESCRIPTOR.test(descriptor) && declaresUnitReturn(lines, m.method);
}

/** Whether the mutant replaces a `return` whose value nothing reads, so no test can kill it. */
export const isNullReturnOnUnit = (m) => m.unitReturn === true && NULL_RETURN.test(m.description);

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
 * Writes the check's report files for `all` mutants: compiler-call and Unit null-return mutants left
 * out, then the undetected mutants `exceptions` accept (out of the score and the findings, listed in
 * `<check>-accepted.txt`). A `full` run also reports the exceptions that match no mutant, and those
 * whose `count` differs from the mutants they match; `extra` adds summary values. Returns the report text.
 */
export function writeReport(outDir, title, all, { exceptions = {}, full = false, extra = {} } = {}) {
  const mutations = all.filter((m) => !isCompilerCallMutant(m) && !isNullReturnOnUnit(m));
  const undetected = mutations.filter((m) => Object.hasOwn(FINDING_STATUSES, m.status));
  const { kept, accepted, stale, miscounted } = applyExceptions(undetected, (m) => m.key, exceptions, { full });
  const undetectedSet = new Set(undetected);
  const scored = [...mutations.filter((m) => !undetectedSet.has(m)), ...kept];
  const stats = { ...pitStats(scored), ignored: all.length - mutations.length, accepted: accepted.length, ...extra };
  const score = stats.score === 'n/a' ? 'no mutants' : `score ${stats.score}%`;
  writeAccepted(outDir, CHECK, accepted);
  return writeCheck(outDir, CHECK, `${title} (${score})`, [...pitFindings(kept), ...staleFindings(stale, BACKEND_EXCEPTIONS), ...countFindings(miscounted, BACKEND_EXCEPTIONS)], stats);
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
