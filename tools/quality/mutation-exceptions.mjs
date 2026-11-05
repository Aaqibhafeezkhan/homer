/**
 * Accepted mutation-test exceptions (ADR-0082): a reviewed file per stack, a JSON object mapping an
 * exception key to the reason the surviving mutant is accepted. Not a baseline: every unaccepted
 * finding stays visible, and an entry that matches no mutant in a full run is itself a finding.
 * A key carries no line number, so it survives edits above the mutant, and it matches every mutant
 * with that key. Only undetected (survived or uncovered) mutants are offered to `applyExceptions`.
 * Keys: backend `<PIT source path>#<mutated method>#<description>` (tools/quality/pit.mjs),
 * console `<file>#<mutator>#<original text>→<replacement>` (console/scripts/mutation.mjs).
 */
import fs from 'node:fs';
import path from 'node:path';

export const BACKEND_EXCEPTIONS = 'tools/quality/mutation-exceptions/backend.json';
export const CONSOLE_EXCEPTIONS = 'tools/quality/mutation-exceptions/console.json';

/** The exceptions of `file` (key → reason); `{}` for a missing file. Throws naming a key without a reason. */
export function loadExceptions(file) {
  if (!fs.existsSync(file)) return {};
  const exceptions = JSON.parse(fs.readFileSync(file, 'utf8'));
  if (exceptions === null || typeof exceptions !== 'object' || Array.isArray(exceptions)) throw new Error(`${file}: expected a JSON object of key → reason`);
  for (const [key, reason] of Object.entries(exceptions)) {
    if (typeof reason !== 'string' || reason.trim() === '') throw new Error(`${file}: accepted exception needs a non-empty reason: ${key}`);
  }
  return exceptions;
}

/**
 * Splits `mutants` by `keyOf` into `kept` and `accepted` (`{ mutant, key, reason }`). `stale` lists
 * the keys matching no mutant, computed only for a `full` run: a changed-lines run sees too few mutants.
 */
export function applyExceptions(mutants, keyOf, exceptions, { full }) {
  const kept = [];
  const accepted = [];
  const seen = new Set();
  for (const mutant of mutants) {
    const key = keyOf(mutant);
    if (Object.hasOwn(exceptions, key)) {
      seen.add(key);
      accepted.push({ mutant, key, reason: exceptions[key] });
    } else {
      kept.push(mutant);
    }
  }
  const stale = full ? Object.keys(exceptions).filter((key) => !seen.has(key)) : [];
  return { kept, accepted, stale };
}

/** One finding per stale key, on the exceptions file. */
export const staleFindings = (stale, file) => stale.map((key) => ({ path: file, line: 1, message: `stale accepted exception: ${key}`, severity: 'minor' }));

/** The accepted exceptions as report lines, `<key>  — <reason>`, one per line. */
export const formatAccepted = (accepted) => accepted.map(({ key, reason }) => `${key}  — ${reason.replace(/\s+/g, ' ').trim()}\n`).join('');

/** Writes `<check>-accepted.txt` into `dir`. */
export function writeAccepted(dir, check, accepted) {
  fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path.join(dir, `${check}-accepted.txt`), formatAccepted(accepted));
}
