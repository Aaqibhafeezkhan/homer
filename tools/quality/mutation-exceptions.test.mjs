import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import fc from 'fast-check';
import { applyExceptions, countFindings, formatAccepted, loadExceptions, staleFindings } from './mutation-exceptions.mjs';

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

test('an <inline> entry is a reason with a count of at least 1; a plain reason or a bad count there is refused, naming its key', () => {
  const inline = 'a.kt#m#d#<inline>';
  const entry = { reason: 'why', count: 2 };
  assert.deepEqual(loadExceptions(write(JSON.stringify({ [inline]: entry, 'a.kt#m#d#x': 'r' }))), { [inline]: entry, 'a.kt#m#d#x': 'r' });
  for (const bad of ['"why"', '{"count": 1}', '{"reason": " ", "count": 1}', '{"reason": "r", "count": 1, "extra": 1}', '[]', 'null']) {
    assert.throws(() => loadExceptions(write(`{"${inline}": ${bad}}`)), /needs \{ "reason": "…", "count": N \}: a\.kt#m#d#<inline>$/, bad);
  }
  for (const count of ['0', '-1', '1.5', '"2"', 'null']) {
    assert.throws(() => loadExceptions(write(`{"${inline}": {"reason": "r", "count": ${count}}}`)), /count of at least 1: a\.kt#m#d#<inline>$/, count);
  }
  assert.throws(() => loadExceptions(write('{"a.kt#m#d#x": {"reason": "r", "count": 1}}')), /non-empty reason: a\.kt#m#d#x$/);
});

test('an entry with a count is miscounted in a full run only when it matches another number of mutants, all of which stay accepted', () => {
  const exceptions = { i: { reason: 'ri', count: 2 } };
  const exact = applyExceptions(['i', 'i'], (m) => m, exceptions, { full: true });
  assert.deepEqual(exact.miscounted, []);
  assert.deepEqual(exact.accepted.map((a) => a.reason), ['ri', 'ri']);
  const more = applyExceptions(['i', 'i', 'i'], (m) => m, exceptions, { full: true });
  assert.deepEqual(more.miscounted, [{ key: 'i', matched: 3, expected: 2 }]);
  assert.equal(more.accepted.length, 3);
  assert.deepEqual(applyExceptions(['i'], (m) => m, exceptions, { full: true }).miscounted, [{ key: 'i', matched: 1, expected: 2 }]);
  assert.deepEqual(applyExceptions(['i', 'i', 'i'], (m) => m, exceptions, { full: false }).miscounted, []);
  const none = applyExceptions([], (m) => m, exceptions, { full: true });
  assert.deepEqual([none.stale, none.miscounted], [['i'], []]);
  assert.deepEqual(countFindings(more.miscounted, 'x/e.json'), [{ path: 'x/e.json', line: 1, message: 'accepted exception matched 3 mutants, expected 2: i', severity: 'minor' }]);
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

test('a loaded entry is a non-empty reason, or for an <inline> key a reason with a whole count of at least 1', () => {
  const key = fc.oneof(fc.string(), fc.string().map((k) => `${k}<inline>`));
  fc.assert(
    fc.property(key, fc.jsonValue(), (k, entry) => {
      let loaded;
      try {
        loaded = loadExceptions(write(JSON.stringify({ [k]: entry })));
      } catch (error) {
        return error.message.endsWith(`: ${k}`);
      }
      const value = loaded[k];
      if (!k.endsWith('<inline>')) return typeof value === 'string' && value.trim() !== '';
      return typeof value.reason === 'string' && value.reason.trim() !== '' && Number.isInteger(value.count) && value.count >= 1;
    }),
    { numRuns: 200 },
  );
});
