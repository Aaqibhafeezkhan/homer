import assert from 'node:assert/strict';
import { test } from 'node:test';
import { parseSarif, sortFindings } from './detekt.mjs';

const result = (ruleId, uri, line, text) => ({
  ruleId,
  message: { text },
  locations: [{ physicalLocation: { artifactLocation: { uri, uriBaseId: '%SRCROOT%' }, region: { startLine: line } } }],
});

const sarif = {
  runs: [
    {
      results: [
        result('detekt.exceptions.TooGenericExceptionCaught', 'app/app/src/main/kotlin/homer/app/A.kt', 9, 'The caught exception is too generic.'),
        result('detekt.coroutines.InjectDispatcher', 'app/app/src/main/kotlin/homer/app/B.kt', 4, 'Dispatcher IO is used without dependency injection.'),
        result('detekt.potential-bugs.MissingUseCall', 'file:///repo/app/homer-runner/src/main/kotlin/R.kt', 2, "open doesn't call `use`"),
        { ruleId: 'detekt.style.NoLocation', message: { text: 'project-level' } },
      ],
    },
  ],
};

test('each located result becomes a repo-relative finding named by rule set and rule', () => {
  const findings = parseSarif(sarif, '/repo');
  assert.deepEqual(
    findings.map((f) => [f.path, f.line, f.severity]),
    [
      ['app/app/src/main/kotlin/homer/app/A.kt', 9, 'minor'],
      ['app/app/src/main/kotlin/homer/app/B.kt', 4, 'major'],
      ['app/homer-runner/src/main/kotlin/R.kt', 2, 'major'],
    ],
  );
  assert.equal(findings[0].message, 'exceptions.TooGenericExceptionCaught: The caught exception is too generic.');
});

test('coroutine and potential-bug findings sort before exception findings', () => {
  const sorted = sortFindings(parseSarif(sarif, '/repo'));
  assert.deepEqual(
    sorted.map((f) => f.message.split(':')[0]),
    ['coroutines.InjectDispatcher', 'potential-bugs.MissingUseCall', 'exceptions.TooGenericExceptionCaught'],
  );
});

test('a report without runs or results has no findings', () => {
  assert.deepEqual(parseSarif({}, '/repo'), []);
  assert.deepEqual(parseSarif({ runs: [{}] }, '/repo'), []);
});
