import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { main, parseJUnit } from './architecture-backend.mjs';

const xml = `<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="ArchitectureTest" tests="3" failures="1" errors="0">
  <testcase name="services have a test()" classname="ArchitectureTest" time="0.1">
    <failure message="2 violation(s)&#10;VIOLATION app/app/src/main/kotlin/homer/app/services/A.kt:5 class A has no ATest" type="AssertionError">java.lang.AssertionError: 2 violation(s)
VIOLATION app/app/src/main/kotlin/homer/app/services/A.kt:5 class A has no ATest
VIOLATION app/app/src/main/kotlin/homer/app/services/B.kt:9 class B has no BTest &amp; no fixture
	at ArchitectureTest.services(ArchitectureTest.kt:20)</failure>
  </testcase>
  <testcase name="no println in main code()" classname="ArchitectureTest" time="0.1"/>
  <testcase name="skipped rule()" classname="ArchitectureTest"><skipped/></testcase>
</testsuite>`;

test('each VIOLATION line of a failed rule is one finding, deduplicated across message and body', () => {
  const findings = parseJUnit(xml);
  assert.deepEqual(
    findings.map((f) => [f.path, f.line, f.message]),
    [
      ['app/app/src/main/kotlin/homer/app/services/A.kt', 5, 'services have a test: class A has no ATest'],
      ['app/app/src/main/kotlin/homer/app/services/B.kt', 9, 'services have a test: class B has no BTest & no fixture'],
    ],
  );
});

test('a count in the finding text does not change its stable key', () => {
  const finding = (count) =>
    parseJUnit(
      `<testcase name="no cycles()"><failure message="x">VIOLATION app/a/B.kt:3 a → b closes the package cycle a ⇄ b (${count} import(s) the other way): b.C</failure></testcase>`,
    )[0];
  assert.equal(finding(108).stableKey, finding(119).stableKey);
  assert.notEqual(finding(108).message, finding(119).message);
});

test('digits inside an identifier still tell two findings apart', () => {
  const key = (name) =>
    parseJUnit(`<testcase name="sizes()"><failure message="x">VIOLATION app/a/B.kt:3 ${name} has a 76-line body (limit 60)</failure></testcase>`)[0]
      .stableKey;
  assert.notEqual(key('migrateToV10'), key('migrateToV11'));
  assert.equal(key('migrateToV10'), key('migrateToV10'));
});

test('a rule that errors or fails without VIOLATION lines is a broken check, not a finding', () => {
  assert.throws(() => parseJUnit('<testcase name="r()"><error message="boom"/></testcase>'), /errored/);
  assert.throws(() => parseJUnit('<testcase name="r()"><failure message="x">x</failure></testcase>'), /without VIOLATION/);
});

test('the check argument names the written files, so the pending lane gets its own summary', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'architecture-backend-'));
  const results = path.join(dir, 'results');
  fs.mkdirSync(results);
  fs.writeFileSync(path.join(results, 'TEST-Rules.xml'), xml);
  const out = path.join(dir, 'out');

  main([results, out, 'architecture-backend-pending']);

  assert.deepEqual(fs.readdirSync(out).sort(), [
    'architecture-backend-pending-report.txt',
    'architecture-backend-pending-summary.txt',
    'gl-code-quality-architecture-backend-pending.json',
  ]);
  assert.match(fs.readFileSync(path.join(out, 'architecture-backend-pending-summary.txt'), 'utf8'), /^findings=2$/m);
  assert.throws(() => main([results, out, 'architecture-console']), /unknown check/);
});
