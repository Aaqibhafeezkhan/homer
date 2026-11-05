/**
 * The whole backend through PIT (check `mutation-backend`, ADR-0082), sharded per package so that
 * each shard fits in one GitHub Actions job (6 hours at most): the Nightly workflow's weekly run.
 * A shard is a directory under the source root that directly holds `.kt` files, named as its package.
 * Usage (repository root, JAVA_HOME on a JDK 25):
 *   node tools/quality/pit-full.mjs shards                the shard names, as a JSON array
 *   node tools/quality/pit-full.mjs run <shard>           PIT on that shard → reports/quality/mutation-backend-shards/<shard>.xml
 *   node tools/quality/pit-full.mjs merge <dir>           the mutation-backend report of every <shard>.xml in <dir>;
 *                                                         a full run only when every shard of the checkout is there
 */
import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { runMain } from './lib.mjs';
import { BACKEND_EXCEPTIONS, loadExceptions } from './mutation-exceptions.mjs';
import { classGlobs, readSource } from './pit-changed.mjs';
import { REPORT_DIR, SOURCE_ROOT, readMutations, sourceReader, writeReport } from './pit.mjs';

export const SHARD_DIR = 'reports/quality/mutation-backend-shards';
const TITLE = 'Backend mutation testing';
// A shard name becomes a file name, an artifact name and a job's matrix value: a package name only.
const SHARD_NAME = /^\w+(?:\.\w+)*$/;

const isKotlin = (entry) => entry.isFile() && entry.name.endsWith('.kt');

/** One shard per directory under `root` that directly holds `.kt` files, named as its package; sorted. */
export function shards(root = SOURCE_ROOT) {
  const found = [];
  const walk = (dir, segments) => {
    const entries = fs.readdirSync(dir, { withFileTypes: true });
    if (segments.length > 0 && entries.some(isKotlin)) {
      const name = segments.join('.');
      if (!SHARD_NAME.test(name)) throw new Error(`${dir}: not a package directory, so not a shard`);
      found.push(name);
    }
    for (const entry of entries) if (entry.isDirectory()) walk(path.join(dir, entry.name), [...segments, entry.name]);
  };
  if (fs.existsSync(root)) walk(root, []);
  return found.sort();
}

/** The PIT class globs of a shard: those of the `.kt` files directly in its directory, not its subdirectories. */
export function shardGlobs(shard, root = SOURCE_ROOT) {
  if (!shards(root).includes(shard)) throw new Error(`unknown shard: ${shard}`);
  const dir = path.join(root, ...shard.split('.'));
  const files = fs.readdirSync(dir, { withFileTypes: true }).filter(isKotlin).map((entry) => path.join(dir, entry.name));
  const globs = new Set(files.flatMap((file) => {
    const source = readSource(file);
    return source === null ? [] : classGlobs(file, source);
  }));
  if (globs.size === 0) throw new Error(`shard ${shard}: no class to mutate`);
  return [...globs].sort();
}

/**
 * PIT on one shard; its `mutations.xml` is copied to `<SHARD_DIR>/<shard>.xml`. An older copy goes
 * first, so a failed rerun leaves no report of an earlier run for `merge` to read.
 */
export function runShard(shard) {
  const globs = shardGlobs(shard);
  const xml = path.join(REPORT_DIR, 'mutations.xml');
  const out = path.join(SHARD_DIR, `${shard}.xml`);
  fs.rmSync(out, { force: true });
  fs.rmSync(xml, { force: true });
  const gradle = spawnSync(path.join('app', 'gradlew'), ['-p', 'app', '--console=plain', ':app:pitest', `-PpitestTargetClasses=${globs.join(',')}`], { stdio: 'inherit' });
  if (gradle.status !== 0) throw new Error(`:app:pitest exited ${gradle.status ?? gradle.error?.message}`);
  if (!fs.existsSync(xml)) throw new Error(`no ${xml}: :app:pitest wrote no report`);
  fs.mkdirSync(SHARD_DIR, { recursive: true });
  fs.copyFileSync(xml, out);
  return `${shard}: ${out}\n`;
}

/**
 * The mutation-backend report of every `<shard>.xml` in `dir`. A full run (stale exceptions judged)
 * only when every shard of the checkout (`shards()`) is there; else the summary names the missing
 * ones as `partial`.
 */
export function merge(dir) {
  const files = fs.existsSync(dir) ? fs.readdirSync(dir, { withFileTypes: true }).filter((e) => e.isFile() && e.name.endsWith('.xml')).map((e) => e.name).sort() : [];
  const present = new Set(files.map((name) => name.slice(0, -'.xml'.length)));
  const missing = shards().filter((shard) => !present.has(shard));
  const read = sourceReader();
  const all = files.flatMap((name) => readMutations(path.join(dir, name), read));
  const extra = missing.length > 0 ? { partial: missing.join(',') } : {};
  return writeReport(REPORT_DIR, TITLE, all, { exceptions: loadExceptions(BACKEND_EXCEPTIONS), full: missing.length === 0, extra });
}

export function main([command, ...args]) {
  if (command === 'shards' && args.length === 0) return `${JSON.stringify(shards())}\n`;
  if (command === 'run' && args.length === 1) return runShard(args[0]);
  if (command === 'merge' && args.length === 1) return merge(args[0]);
  throw new Error('usage: node tools/quality/pit-full.mjs shards | run <shard> | merge <dir>');
}

runMain(import.meta, main);
