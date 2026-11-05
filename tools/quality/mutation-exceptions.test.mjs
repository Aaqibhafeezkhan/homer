import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import fc from 'fast-check';
import { applyExceptions, formatAccepted, loadExceptions, staleFindings } from './mutation-exceptions.mjs';

const write = (text) => {
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'exceptions-')), 'e.json');
  fs.writeFileSync(file, text);
  return file;
};

test('a missing file holds no exceptions and a valid one loads', () => {
  assert.deepEqual(loadExceptions(path.join(os.tmpdir(), 'no-such-exceptions.json')), {});
  assert.deepEqual(loadExceptions(write('{"a#b#c": "why"}')), { 'a#b#c': 'why' });
});

test('an entry without a usable reason is refused, naming its key', () => {
  for (const reason of ['""', '"   "', '7', 'null', '{}']) {
    assert.throws(() => loadExceptions(write(`{"ok": "fine", "bad#key#x": ${reason}}`)), /non-empty reason: bad#key#x/);
  }
  assert.throws(() => loadExceptions(write('[]')), /expected a JSON object/);
});

test('mutants split into kept and accepted, with the reason; stale keys only in a full run', () => {
  const exceptions = { a: 'ra', gone: 'rg' };
  const mutants = ['a', 'b', 'a'];
  const full = applyExceptions(mutants, (m) => m, exceptions, { full: true });
  assert.deepEqual(full.kept, ['b']);
  assert.deepEqual(full.accepted, [{ mutant: 'a', key: 'a', reason: 'ra' }, { mutant: 'a', key: 'a', reason: 'ra' }]);
  assert.deepEqual(full.stale, ['gone']);
  assert.deepEqual(applyExceptions(mutants, (m) => m, exceptions, { full: false }).stale, []);
});

test('inherited object keys are not exceptions', () => {
  assert.deepEqual(applyExceptions(['toString', '__proto__'], (m) => m, {}, { full: true }).kept, ['toString', '__proto__']);
});

test('stale entries become minor findings on the exceptions file; accepted lines carry the reason', () => {
  assert.deepEqual(staleFindings(['k'], 'x/e.json'), [{ path: 'x/e.json', line: 1, message: 'stale accepted exception: k', severity: 'minor' }]);
  assert.equal(formatAccepted([{ key: 'k', reason: 'because\nof' }]), 'k  — because of\n');
});

test('kept and accepted always partition the mutants', () => {
  fc.assert(
    fc.property(fc.array(fc.string()), fc.array(fc.string()), fc.boolean(), (mutants, keys, full) => {
      const exceptions = Object.fromEntries(keys.map((k) => [k, 'r']));
      const result = applyExceptions(mutants, (m) => m, exceptions, { full });
      assert.deepEqual([...result.kept, ...result.accepted.map((a) => a.mutant)].sort(), [...mutants].sort());
      assert.ok(result.accepted.every((a) => Object.hasOwn(exceptions, a.key)));
      assert.ok(result.kept.every((m) => !Object.hasOwn(exceptions, m)));
    }),
  );
});
