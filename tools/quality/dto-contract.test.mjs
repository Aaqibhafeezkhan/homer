import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import { CONSOLE_ONLY, RENAMES, compare, main } from './dto-contract.mjs';

const NO_TABLES = { renames: {}, consoleOnly: {}, unmirrored: {} };

const kField = (name, type, { optional = false, nullable = false } = {}) => ({ name, optional, nullable, type });
const tField = (name, type, line, { optional = false } = {}) => ({ name, line, optional, type });
const S = { kind: 'string' };
const N = { kind: 'number' };

const backend = (classes) => ({ discriminator: 'type', classes });
const consoleOf = (declarations) => ({ file: 'console/types/homer.ts', declarations });
const messages = (result) => result.findings.map((f) => `${f.line} ${f.severity} ${f.message}`);

const response = (fields) => ({ serialName: 'x', usage: ['response'], kind: 'class', fields });

test('a mirror that matches its DTO has no findings', () => {
  const result = compare(
    backend({ A: response([kField('id', N), kField('note', S, { optional: true, nullable: true })]) }),
    consoleOf({ A: { line: 1, kind: 'object', fields: [tField('id', N, 2), tField('note', { kind: 'string', nullable: true }, 3, { optional: true })] } }),
    NO_TABLES,
  );
  assert.deepEqual(result.findings, []);
  assert.equal(result.compared, 1);
});

test('missing, extra, null-excluding and mistyped fields of a response are findings', () => {
  const result = compare(
    backend({ A: response([kField('id', N), kField('gone', S), kField('note', S, { nullable: true }), kField('count', N)]) }),
    consoleOf({
      A: { line: 1, kind: 'object', fields: [tField('id', N, 2), tField('note', S, 3, { optional: true }), tField('count', S, 4), tField('extra', S, 5)] },
    }),
    NO_TABLES,
  );
  assert.deepEqual(messages(result), [
    '1 minor A lacks gone, which Kotlin A has',
    '3 major A.note does not allow null, but Kotlin A.note is nullable',
    '4 major A.count is string, but Kotlin has number',
    '5 major A.extra has no field in Kotlin A',
  ]);
});

test('a request must not leave out or null a field Kotlin requires', () => {
  const result = compare(
    backend({ R: { serialName: 'r', usage: ['request'], kind: 'class', fields: [kField('id', N), kField('name', S), kField('env', S, { optional: true })] } }),
    consoleOf({ R: { line: 1, kind: 'object', fields: [tField('id', N, 2, { optional: true }), tField('env', { kind: 'string', nullable: true }, 3)] } }),
    NO_TABLES,
  );
  assert.deepEqual(messages(result), [
    '1 major R lacks name, which Kotlin R requires',
    '2 major R.id is optional, but Kotlin R.id is required',
    '3 major R.env allows null, but Kotlin R.env does not',
  ]);
});

test('enum values are compared both ways, and a String field accepts any string-like TS type', () => {
  const result = compare(
    backend({
      Status: { serialName: 's', usage: ['response'], kind: 'enum', values: ['A', 'B'] },
      Row: response([kField('status', S), kField('kind', { kind: 'enum', class: 'Status' })]),
    }),
    consoleOf({
      Status: { line: 1, kind: 'enum', values: ['A', 'C'] },
      Mixed: { line: 2, kind: 'union', members: [{ kind: 'ref', name: 'Status' }, { kind: 'enum', values: ['mixed'] }] },
      Row: { line: 3, kind: 'object', fields: [tField('status', { kind: 'ref', name: 'Mixed' }, 4), tField('kind', { kind: 'ref', name: 'Status' }, 5)] },
    }),
    { ...NO_TABLES, consoleOnly: { Mixed: 'test' } },
  );
  assert.deepEqual(messages(result), ["1 minor Status has 'C', which the backend never sends", "1 major Status lacks 'B', which the backend sends"]);
});

test('renames match differently named mirrors, and a field must use the mirror of its Kotlin class', () => {
  const result = compare(
    backend({
      ItemResponse: response([kField('id', N)]),
      Other: response([kField('id', N)]),
      List: response([kField('items', { kind: 'list', of: { kind: 'class', class: 'ItemResponse' } }), kField('one', { kind: 'class', class: 'Other' })]),
    }),
    consoleOf({
      Item: { line: 1, kind: 'object', fields: [tField('id', N, 2)] },
      Other: { line: 3, kind: 'object', fields: [tField('id', N, 4)] },
      List: { line: 5, kind: 'object', fields: [tField('items', { kind: 'list', of: { kind: 'ref', name: 'Item' } }, 6), tField('one', { kind: 'ref', name: 'Item' }, 7)] },
    }),
    { ...NO_TABLES, renames: { Item: 'ItemResponse' } },
  );
  assert.deepEqual(messages(result), ['7 major List.one is Item, but Kotlin has class Other, mirrored by Other']);
});

test('a TS type with no DTO, an unmirrored DTO and stale table entries are findings', () => {
  const result = compare(
    backend({ Lonely: response([kField('id', N)]) }),
    consoleOf({ Orphan: { line: 4, kind: 'object', fields: [] } }),
    { renames: { Gone: 'GoneResponse' }, consoleOnly: { Old: 'was removed' }, unmirrored: {} },
  );
  assert.deepEqual(messages(result), [
    '1 minor CONSOLE_ONLY lists Old (was removed), which console/types/homer.ts no longer declares',
    '1 minor Kotlin Lonely is exported, but no TS type mirrors it',
    '1 minor RENAMES maps Gone to Kotlin GoneResponse, but console/types/homer.ts no longer declares it',
    '4 major Orphan has no exported Kotlin DTO Orphan: add it to DtoContract, RENAMES or CONSOLE_ONLY',
  ]);
});

test('a field listed as deliberately unmirrored is not reported missing', () => {
  const result = compare(
    backend({ G: response([kField('label', S), kField('mermaid', S, { optional: true, nullable: true })]) }),
    consoleOf({ G: { line: 1, kind: 'object', fields: [tField('label', S, 2)] } }),
    { ...NO_TABLES, unmirrored: { 'G.mermaid': 'deprecated' } },
  );
  assert.deepEqual(result.findings, []);
});

const sealed = {
  serialName: 'Event',
  usage: ['response'],
  kind: 'sealed',
  subclasses: [
    { serialName: 'start', fields: [kField('id', S, { nullable: true })] },
    { serialName: 'stop', fields: [kField('reason', S)] },
  ],
};
const tag = (value, line) => tField('type', { kind: 'enum', values: [value] }, line);

test('a TS union mirrors a sealed class variant by variant through the discriminator', () => {
  const result = compare(
    backend({ Event: sealed }),
    consoleOf({
      Start: { line: 1, kind: 'object', fields: [tag('start', 1), tField('id', S, 1)] },
      Event: {
        line: 3,
        kind: 'union',
        members: [{ kind: 'ref', name: 'Start' }, { kind: 'object', fields: [tag('pause', 4)] }, { kind: 'object', fields: [tField('x', S, 5)] }],
      },
    }),
    NO_TABLES,
  );
  assert.deepEqual(messages(result), [
    '1 major Start.id does not allow null, but Kotlin Event<start>.id is nullable',
    "3 major Event lacks the 'stop' variant, which Kotlin Event sends",
    "4 major Event<pause> is variant 'pause', which Kotlin Event does not have",
    "5 major Event member object has no single 'type' literal",
  ]);
});

test('one TS interface for a sealed class holds the discriminator and every subclass field', () => {
  const result = compare(
    backend({ Event: sealed }),
    consoleOf({
      Event: {
        line: 1,
        kind: 'object',
        fields: [tField('type', { kind: 'enum', values: ['start', 'other'] }, 2), tField('id', { kind: 'string', nullable: true }, 3, { optional: true }), tField('reason', S, 4)],
      },
    }),
    NO_TABLES,
  );
  assert.deepEqual(messages(result), [
    "2 minor Event.type has 'other', which the backend never sends",
    "2 major Event.type lacks 'stop', which the backend sends",
    '4 major Event.reason is required, but only some Kotlin Event subclasses have it',
  ]);
});

test('the shipped tables name each TS type at most once', () => {
  const overlap = Object.keys(RENAMES).filter((name) => name in CONSOLE_ONLY);
  assert.deepEqual(overlap, []);
});

test('main writes the contract files and fails on a missing input', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'dto-contract-'));
  const backendFile = path.join(dir, 'b.json');
  const consoleFile = path.join(dir, 'c.json');
  fs.writeFileSync(backendFile, JSON.stringify(backend({})));
  fs.writeFileSync(consoleFile, JSON.stringify(consoleOf({ ProcessFilters: { line: 1, kind: 'object', fields: [] } })));
  const out = path.join(dir, 'out');
  const report = main([backendFile, consoleFile, out]);
  assert.match(report, /Console DTO mirrors vs backend DTOs: \d+ finding\(s\)/);
  assert.match(fs.readFileSync(path.join(out, 'dto-contract-summary.txt'), 'utf8'), /^findings=\d+\ncompared=0\nbackendClasses=0\nconsoleTypes=1\n$/);
  assert.ok(fs.existsSync(path.join(out, 'gl-code-quality-dto-contract.json')));
  assert.throws(() => main([path.join(dir, 'missing.json'), consoleFile, out]), /not found: did :app:test run DtoContractTest\?/);
});

test('inline objects are compared field by field, and a shape of the wrong kind is one finding', () => {
  const result = compare(
    backend({
      Color: { serialName: 'c', usage: ['response'], kind: 'enum', values: ['red'] },
      Inner: response([kField('n', N)]),
      Outer: response([
        kField('inner', { kind: 'class', class: 'Inner' }),
        kField('color', { kind: 'enum', class: 'Color' }),
        kField('raw', { kind: 'json' }),
        kField('later', { kind: 'class', class: 'Inner' }),
        kField('tags', { kind: 'map', of: N }),
      ]),
    }),
    consoleOf({
      Outer: {
        line: 1,
        kind: 'object',
        fields: [
          tField('inner', { kind: 'object', fields: [tField('n', S, 3)] }, 2),
          tField('color', S, 4),
          tField('raw', { kind: 'list', of: N }, 5),
          tField('later', { kind: 'ref', name: 'Undeclared' }, 6),
          tField('tags', { kind: 'map', of: S }, 7),
        ],
      },
      Inner: { line: 8, kind: 'enum', values: ['n'] },
      Color: { line: 9, kind: 'object', fields: [] },
    }),
    NO_TABLES,
  );
  assert.deepEqual(messages(result), [
    '3 major Outer.inner.n is string, but Kotlin has number',
    '6 major Outer.later is Undeclared, but Kotlin has class Inner, mirrored by Inner',
    '7 major Outer.tags{} is string, but Kotlin has number',
    "8 major Inner is 'n', but Kotlin Inner is a class",
    '9 major Color is object, but Kotlin Color is an enum',
  ]);
});
