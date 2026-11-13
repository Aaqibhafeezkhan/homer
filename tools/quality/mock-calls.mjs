/**
 * mock-calls: tests during `:app:test` in which one mock method was called more often than
 * `MockCallBudget.WARN_CALLS`, the shape of a stub that answers at once inside a polling loop
 * (ADR-0082 amendment of 2026-11-13, `app/CLAUDE.md` § Testing rules). `MockCallBudget` appends one
 * JSON line per such test to the report; past `MockCallBudget.FAIL_CALLS` it also fails the test.
 * Usage (repository root): node tools/quality/mock-calls.mjs [report dir] [out dir]
 */
import fs from 'node:fs';
import path from 'node:path';
import { runMain, writeCheck } from './lib.mjs';

const REPORT_DIR = 'app/app/build/reports/mock-calls';
const REPORT_FILE = 'mock-calls.jsonl';
const TEST_ROOT = 'app/app/src/test/kotlin';

/** The source file of a test class: its outermost class's file, named after the class (Kotlin's convention here). */
export function testFile(className) {
  const outer = className.split('$')[0];
  return `${TEST_ROOT}/${outer.replaceAll('.', '/')}.kt`;
}

/** 1-based line of the test function `name` in `source`, or 1 when it is not found (a parameterised display name, say). */
export function testLine(source, name) {
  const bare = name.replace(/\(.*\)$/, '');
  const at = source.split('\n').findIndex((l) => l.includes(`fun \`${bare}\``) || l.includes(`fun ${bare}(`));
  return at < 0 ? 1 : at + 1;
}

/** Findings of the report's lines; `read` returns a test file's text, or undefined when it is missing. */
export function parseReport(lines, read) {
  return lines
    .map((line) => JSON.parse(line))
    .map((e) => {
      const file = testFile(e.testClass);
      const source = read(file);
      return {
        path: file,
        line: source === undefined ? 1 : testLine(source, e.test),
        severity: 'minor',
        message:
          `${e.test}: ${e.method} was called ${e.calls} times in one test (warning from ${e.warnAt}, failure from ${e.failAt}): ` +
          'a stub that answers at once inside a polling loop? Make the fake wait as the real call does (CLAUDE.md §6)',
      };
    })
    .sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line);
}

export function main([reportDir = REPORT_DIR, outDir = 'reports/quality/mock-calls']) {
  if (!fs.existsSync(reportDir)) throw new Error(`no ${reportDir}: did :app:test run?`);
  const file = path.join(reportDir, REPORT_FILE);
  const lines = fs.existsSync(file) ? fs.readFileSync(file, 'utf8').split('\n').filter((l) => l.trim() !== '') : [];
  const read = (f) => (fs.existsSync(f) ? fs.readFileSync(f, 'utf8') : undefined);
  const findings = parseReport(lines, read);
  return writeCheck(outDir, 'mock-calls', 'Mock methods called in a loop', findings, { tests: lines.length }).split('\n').slice(0, 21).join('\n') + '\n';
}

runMain(import.meta, main);
