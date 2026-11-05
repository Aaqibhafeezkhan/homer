import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';
import fc from 'fast-check';
import { main, shards } from './pit-full.mjs';

const SRC = 'app/app/src/main/kotlin';
const REPORT = 'app/app/build/reports/pitest';
const write = (dir, file, text, mode) => {
  fs.mkdirSync(path.dirname(path.join(dir, file)), { recursive: true });
  fs.writeFileSync(path.join(dir, file), text, { mode });
};
const mutation = (cls, file, line, status = 'SURVIVED') =>
  `<mutation status='${status}'><sourceFile>${file}</sourceFile><mutatedClass>${cls}</mutatedClass><mutatedMethod>f</mutatedMethod><lineNumber>${line}</lineNumber><description>d</description></mutation>`;
const xml = (...mutations) => `<mutations>${mutations.join('')}</mutations>`;

/**
 * A temporary repository holding `files` and a stand-in for Gradle that records its arguments,
 * writes `app/report.xml` as PIT's report and exits `exit`. Runs `fn(dir)` with it as the working
 * directory; returns fn's result (or the error it threw) and the recorded Gradle arguments, if any.
 */
function inRepo(files, fn, { exit = 0 } = {}) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-full-'));
  for (const [file, text] of Object.entries(files)) write(dir, file, text);
  write(dir, 'app/gradlew', `#!/bin/sh\nprintf "%s\\n" "$@" > app/args.txt\nmkdir -p ${REPORT}\ncp app/report.xml ${REPORT}/mutations.xml\nexit ${exit}\n`, 0o755);
  const cwd = process.cwd();
  process.chdir(dir);
  try {
    let output;
    try {
      output = fn(dir);
    } catch (error) {
      output = error;
    }
    const argsFile = path.join(dir, 'app/args.txt');
    return { output, args: fs.existsSync(argsFile) ? fs.readFileSync(argsFile, 'utf8').trim().split('\n') : null };
  } finally {
    process.chdir(cwd);
  }
}

const SOURCES = {
  [`${SRC}/homer/app/Application.kt`]: 'package homer.app\n\nfun main() {}\n',
  [`${SRC}/homer/app/infra/PathSafety.kt`]: 'package homer.app.infra\n\nobject PathSafety\n',
  [`${SRC}/homer/app/infra/llm/Price.kt`]: 'package homer.app.infra.llm\n\nclass Price\n',
  [`${SRC}/homer/app/empty/README.md`]: 'no Kotlin here\n',
};

test('a shard per directory that directly holds Kotlin sources, the root package included, sorted', () => {
  const { output } = inRepo(SOURCES, () => main(['shards']));
  assert.equal(output, '["homer.app","homer.app.infra","homer.app.infra.llm"]\n');
});

test('a directory that is no package name is refused rather than made a shard', () => {
  const { output } = inRepo({ [`${SRC}/homer/app-x/A.kt`]: 'package homer.appx\n' }, () => shards());
  assert.match(output.message, /not a package directory/);
});

test('a shard name is a package name whatever the directory is called: safe as a file and an artifact name', () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pit-full-names-'));
  const segment = fc.string({ minLength: 1, maxLength: 8 }).filter((s) => !s.includes('/') && !s.includes('\0') && s !== '.' && s !== '..');
  fc.assert(
    fc.property(fc.array(segment, { minLength: 1, maxLength: 3 }), (segments) => {
      const sources = fs.mkdtempSync(path.join(root, 's-'));
      fs.mkdirSync(path.join(sources, ...segments), { recursive: true });
      fs.writeFileSync(path.join(sources, ...segments, 'A.kt'), 'package a\n');
      let names;
      try {
        names = shards(sources);
      } catch (error) {
        return /not a package directory/.test(error.message);
      }
      return names.every((name) => /^\w+(?:\.\w+)*$/.test(name));
    }),
    { numRuns: 50 },
  );
});

test('run mutates only the classes directly in the shard directory and keeps its report under the shard name', () => {
  const files = { ...SOURCES, 'app/report.xml': xml(mutation('homer.app.infra.PathSafety', 'PathSafety.kt', 3)) };
  const { output, args } = inRepo(files, (dir) => [main(['run', 'homer.app.infra']), fs.readFileSync(path.join(dir, 'reports/quality/mutation-backend-shards/homer.app.infra.xml'), 'utf8')]);
  assert.deepEqual(args, ['-p', 'app', '--console=plain', ':app:pitest', '-PpitestTargetClasses=homer.app.infra.PathSafety,homer.app.infra.PathSafety$*,homer.app.infra.PathSafetyKt,homer.app.infra.PathSafetyKt$*']);
  assert.equal(output[0], 'homer.app.infra: reports/quality/mutation-backend-shards/homer.app.infra.xml\n');
  assert.equal(output[1], files['app/report.xml']);
});

test('run refuses an unknown shard without running Gradle', () => {
  for (const shard of ['homer.app.missing', 'homer.app.empty', '../homer.app', 'homer']) {
    const { output, args } = inRepo(SOURCES, () => main(['run', shard]));
    assert.match(output.message, /unknown shard/, shard);
    assert.equal(args, null);
  }
});

test('run fails when Gradle fails, and keeps no shard report', () => {
  const { output } = inRepo({ ...SOURCES, 'app/report.xml': xml() }, (dir) => {
    try {
      return main(['run', 'homer.app']);
    } finally {
      assert.equal(fs.existsSync(path.join(dir, 'reports/quality/mutation-backend-shards/homer.app.xml')), false);
    }
  }, { exit: 1 });
  assert.match(output.message, /:app:pitest exited 1/);
});

test('run fails when Gradle exits 0 but wrote no report, and keeps no shard report', () => {
  const { output } = inRepo(SOURCES, (dir) => {
    try {
      return main(['run', 'homer.app']);
    } finally {
      assert.equal(fs.existsSync(path.join(dir, 'reports/quality/mutation-backend-shards/homer.app.xml')), false);
    }
  });
  assert.match(output.message, /wrote no report/);
});

test('a failed rerun of a shard leaves no report of its earlier run', () => {
  const earlier = 'reports/quality/mutation-backend-shards/homer.app.xml';
  const { output } = inRepo({ ...SOURCES, 'app/report.xml': xml(), [earlier]: xml(mutation('homer.app.ApplicationKt', 'Application.kt', 3)) }, (dir) => {
    try {
      return main(['run', 'homer.app']);
    } finally {
      assert.equal(fs.existsSync(path.join(dir, earlier)), false);
    }
  }, { exit: 1 });
  assert.match(output.message, /:app:pitest exited 1/);
});

const SHARD_REPORTS = {
  'shards/homer.app.xml': xml(mutation('homer.app.ApplicationKt', 'Application.kt', 3), mutation('homer.app.ApplicationKt', 'Application.kt', 3, 'KILLED')),
  'shards/homer.app.infra.xml': xml(mutation('homer.app.infra.PathSafety', 'PathSafety.kt', 3)),
  'tools/quality/mutation-exceptions/backend.json': JSON.stringify({ 'homer/app/Gone.kt#f#d#x': 'reason' }),
};
const LAST_SHARD = { 'shards/homer.app.infra.llm.xml': xml(mutation('homer.app.infra.llm.Price', 'Price.kt', 3, 'KILLED')) };
const summary = (dir) => fs.readFileSync(path.join(dir, REPORT, 'mutation-backend-summary.txt'), 'utf8');

test('merge reports the mutants of every shard and, with every shard of the checkout there, judges stale exceptions', () => {
  const { output } = inRepo({ ...SOURCES, ...SHARD_REPORTS, ...LAST_SHARD }, (dir) => [main(['merge', 'shards']), summary(dir)]);
  const [report, values] = output;
  assert.match(report, /^Backend mutation testing \(score 50\.0%\): 3 finding\(s\)\n/);
  assert.match(report, /homer\/app\/Application\.kt:3 {2}survived/);
  assert.match(report, /homer\/app\/infra\/PathSafety\.kt:3 {2}survived/);
  assert.match(report, /stale accepted exception: homer\/app\/Gone\.kt#f#d#x/);
  assert.doesNotMatch(values, /partial=/);
});

test('a merge missing a shard of the checkout is partial: it names the missing shards and judges no stale exception', () => {
  const { output } = inRepo({ ...SOURCES, ...SHARD_REPORTS }, (dir) => [main(['merge', 'shards']), summary(dir)]);
  const [report, values] = output;
  assert.match(report, /: 2 finding\(s\)\n/);
  assert.doesNotMatch(report, /stale/);
  assert.match(values, /\npartial=homer\.app\.infra\.llm\n$/);
});

test('a merge of a single shard\'s report is partial, not a full run that finds every other entry stale', () => {
  const files = { ...SOURCES, 'shards/homer.app.infra.xml': SHARD_REPORTS['shards/homer.app.infra.xml'], 'tools/quality/mutation-exceptions/backend.json': SHARD_REPORTS['tools/quality/mutation-exceptions/backend.json'] };
  const { output } = inRepo(files, (dir) => [main(['merge', 'shards']), summary(dir)]);
  assert.match(output[0], /: 1 finding\(s\)\n/);
  assert.doesNotMatch(output[0], /stale/);
  assert.match(output[1], /\npartial=homer\.app,homer\.app\.infra\.llm\n$/);
});

test('a merge refuses an XML that is no shard of the checkout, naming it, rather than merge a stale report', () => {
  const { output } = inRepo({ ...SOURCES, ...SHARD_REPORTS, ...LAST_SHARD, 'shards/homer.app.oldname.xml': xml(mutation('homer.app.oldname.X', 'X.kt', 3)) }, () => main(['merge', 'shards']));
  assert.match(output.message, /not a shard of this checkout.*homer\.app\.oldname\.xml/);
});

test('shards of a missing source root is none', () => {
  assert.deepEqual(shards(path.join(os.tmpdir(), 'pit-full-no-such-root')), []);
});

test('a merge with no shard report at all is partial on every shard', () => {
  const { output } = inRepo(SOURCES, (dir) => [main(['merge', 'missing']), summary(dir)]);
  assert.match(output[0], /\(no mutants\): 0 finding\(s\)/);
  assert.match(output[1], /\npartial=homer\.app,homer\.app\.infra,homer\.app\.infra\.llm\n$/);
});

test('merge takes no shard list: the shards are those of the checkout', () => {
  assert.throws(() => main(['merge', 'shards', 'homer.app.infra']), /usage: node tools\/quality\/pit-full\.mjs/);
});

test('an unknown command prints the usage', () => {
  assert.throws(() => main(['all']), /usage: node tools\/quality\/pit-full\.mjs/);
});
