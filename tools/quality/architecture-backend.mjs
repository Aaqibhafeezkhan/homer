/**
 * architecture-backend: the Konsist rule violations of `:architecture:test` (ADR-0082), read from
 * its JUnit XML. A violated rule is a failed testcase whose message lists one
 * `VIOLATION <path>:<line> <text>` line per offending declaration.
 * Usage: node tools/quality/architecture-backend.mjs [test-results dir] [out dir] [check]
 * The check `architecture-backend-pending` reads the report-only rules of `:architecture:architectureReportOnly`.
 */
import fs from 'node:fs';
import path from 'node:path';
import { runMain, writeCheck } from './lib.mjs';

const TESTCASE = /<testcase\b([^>]*?)(?:\/>|>([\s\S]*?)<\/testcase>)/g;
const VIOLATION = /^\s*VIOLATION (\S+?):(\d+) (.+)$/;

const attribute = (attrs, name) => new RegExp(`\\b${name}="([^"]*)"`).exec(attrs)?.[1] ?? '';

const unescapeXml = (text) =>
  text
    .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#(\d+);/g, (_, code) => String.fromCharCode(Number(code)))
    .replace(/&amp;/g, '&');

/** Findings of one JUnit XML file; a testcase that errored (not failed) is a broken rule, thrown. */
export function parseJUnit(xml) {
  const findings = [];
  for (const [, attrs, body = ''] of xml.matchAll(TESTCASE)) {
    const rule = unescapeXml(attribute(attrs, 'name')).replace(/\(\)$/, '');
    if (/<error\b/.test(body)) throw new Error(`architecture rule "${rule}" errored instead of reporting violations`);
    const failure = /<failure\b([^>]*?)(?:\/>|>([\s\S]*?)<\/failure>)/.exec(body);
    if (!failure) continue;
    // Gradle puts the whole assertion text in the element body; the message attribute is the fallback.
    const text = unescapeXml(failure[2]?.trim() ? failure[2] : attribute(failure[1], 'message'));
    const violations = text.split('\n').map((line) => VIOLATION.exec(line)).filter(Boolean);
    if (violations.length === 0) throw new Error(`architecture rule "${rule}" failed without VIOLATION lines`);
    const unique = new Map(violations.map(([, file, line, what]) => [`${file}:${line}:${what}`, { file, line, what: what.trim() }]));
    for (const { file, line, what } of unique.values()) {
      // Standalone counts in the text (import totals, line counts) move with unrelated edits; the finding does
      // not. Digits inside an identifier (`migrateToV10`) are no count and still tell two findings apart.
      findings.push({ path: file, line: Number(line), message: `${rule}: ${what}`, stableKey: `${rule}|${what.replace(/\b\d+\b/g, '#')}` });
    }
  }
  return findings;
}

const TITLES = {
  'architecture-backend': 'Backend architecture rules',
  'architecture-backend-pending': 'Backend architecture rules, pending (report-only)',
};

export function main([
  resultsDir = 'app/architecture/build/test-results/test',
  outDir = 'reports/quality/architecture-backend',
  check = 'architecture-backend',
]) {
  if (!(check in TITLES)) throw new Error(`unknown check ${check}: expected one of ${Object.keys(TITLES).join(', ')}`);
  const files = fs.existsSync(resultsDir) ? fs.readdirSync(resultsDir).filter((f) => f.endsWith('.xml')) : [];
  if (files.length === 0) throw new Error(`no JUnit XML in ${resultsDir}: did the architecture tests run?`);
  const findings = files
    .sort()
    .flatMap((file) => parseJUnit(fs.readFileSync(path.join(resultsDir, file), 'utf8')))
    .sort((a, b) => a.path.localeCompare(b.path) || a.line - b.line);
  return writeCheck(outDir, check, TITLES[check], findings).split('\n').slice(0, 21).join('\n') + '\n';
}

runMain(import.meta, main);
