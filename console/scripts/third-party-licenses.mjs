#!/usr/bin/env node
/**
 * Renders the `## Homer console` region of the repository's THIRD-PARTY-LICENSES.md from the
 * production dependency graph in `package-lock.json`. Policy: ADR-0056.
 *
 * The console half of the inventory the backend's :app:generateThirdPartyNotices writes; the
 * rendering rules (grouping, bullet shape, copyright-line extraction, region replacement) mirror
 * `app/buildSrc/src/main/kotlin/ThirdPartyNotices.kt` so both regions read as one document.
 *
 * Input is the lock's production graph, not the built `.next/standalone` tree; ADR-0056's addendum
 * says why.
 *
 * Node built-ins only, on purpose: a dependency of its own would be one more package to inventory.
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const CONSOLE_DIR = path.join(import.meta.dirname, '..');
const INVENTORY_FILE = path.join(import.meta.dirname, '..', '..', 'THIRD-PARTY-LICENSES.md');

const REGION_BEGIN = '<!-- BEGIN GENERATED: console (npm run licenses:generate) -->';
const REGION_END = '<!-- END GENERATED: console -->';

/** The appendix heading the `### <name>` license-text sections live under. */
const LICENSE_TEXTS_HEADING = '## License texts';

const MAX_COPYRIGHT_LINES = 5;

// The sharp image-processing binaries: console/Dockerfile deletes @img/* and sharp from the
// standalone tree before the runner copies it (image optimization is off, so they are never
// loaded), and the build-homer-console license gate fails the push if that stops working. Never
// shipped, so never attributed — which is also what keeps the @img LGPL-3.0 binaries out.
function isSkipped(name) {
  return name === 'sharp' || name.startsWith('@img/');
}

/**
 * Whether a lock path lies in a pruned subtree: the prune removes a skipped package's whole
 * directory, nested node_modules included. Reading only the last segment would attribute what
 * the prune drops, and only on an npm that happened to unpack it, so the gate would turn on the
 * npm version.
 */
function isSkippedPath(lockPath) {
  return lockPath
    .split(NODE_MODULES_SEGMENT)
    .slice(1)
    .some((segment) => isSkipped(segment.replace(/\/$/, '')));
}

// Packages whose lock entry and manifest both declare no license. The license each one actually
// ships, read from its own license file — any other unlicensed package fails the run rather than
// being guessed at.
const LICENSE_OVERRIDES = {
  khroma: 'MIT', // node_modules/khroma/license: "The MIT License (MIT)"
};

// npm declares SPDX ids; the backend region uses jk1's display names. Ids that already have an
// appendix section map onto that section's name so one text serves both regions. Every other id
// is its own heading and gets its own appendix section.
const APPENDIX_NAMES = {
  'Apache-2.0': 'Apache License, Version 2.0',
  MIT: 'MIT License',
  'BSD-3-Clause': 'The 3-Clause BSD License',
  'MIT-0': 'MIT-0',
  'EPL-1.0': 'Eclipse Public License - v 1.0',
  'EPL-2.0': 'Eclipse Public License - v 2.0',
  'LGPL-2.1': 'GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1',
  'LGPL-2.1-only': 'GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1',
  'GPL-2.0': 'GNU GENERAL PUBLIC LICENSE, Version 2',
  'GPL-2.0-only': 'GNU GENERAL PUBLIC LICENSE, Version 2',
  'GPL-2.0-with-classpath-exception': 'GNU GENERAL PUBLIC LICENSE, Version 2 + Classpath Exception',
  'CDDL-1.0': 'COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL) Version 1.0',
};

const LEGAL_FILE_NAME = /^(licen[cs]e|notice|copying)/i;
// An attribution line opens with "Copyright" (optionally behind a (c)/©/Portions marker).
// Matching "copyright" anywhere instead pulls in license boilerplate that also carries a digit —
// "2. Grant of Copyright License…" from every embedded Apache-2.0 LICENSE.
const COPYRIGHT_LINE = /^(\(c\)|©|portions)?\s*copyright\b/i;
const NAMES_A_HOLDER = /\d|\(c\)|©/i;
// Everything up to and including the copyright marker, so what remains is the holder.
const COPYRIGHT_MARKER = /^(\(c\)|©|portions)?\s*copyright\s*(\(c\)|©)?[\s,:;.-]*/i;
// A holder named without a year or marker ("Copyright Eemeli Aro"). Requiring the lowercase second
// letter is what still rejects the all-caps warranty boilerplate ("COPYRIGHT HOLDERS BE LIABLE").
const HOLDER_NAME = /^[A-Z][a-z]/;
// A license's "how to apply these terms" template, reproduced inside the license text itself:
// <year>, <YEAR>, [yyyy], {yyyy}, [name of copyright owner]. Case-sensitive on purpose — a
// placeholder is written all-lowercase or all-caps, so a bracketed holder ("[Ohad Shai]") and an
// e-mail or URL in angle brackets are not mistaken for one.
const TEMPLATE_BRACKETS = /[<[{](?:[a-z][a-z _-]*|[A-Z][A-Z _-]*)[>\]}]/;
// The same template written without brackets. Kept apart from TEMPLATE_BRACKETS so it can be
// case-insensitive — the GPL's own text spells it in caps — without loosening the bracket forms.
const TEMPLATE_PHRASE = /\byear name of author\b/i;
// Steward of a license text, never a holder of the dependency shipping that text.
const LICENSE_STEWARD = /free software foundation/i;

const EXPRESSION_OPERATOR = /\s+(OR|AND)\s+/i;

const NODE_MODULES_SEGMENT = 'node_modules/';

/** The appendix section name for one SPDX id. */
function appendixName(licenseId) {
  return APPENDIX_NAMES[licenseId] ?? licenseId;
}

/**
 * Grouping heading for a declared license: an SPDX expression is stripped of its parentheses and
 * each id mapped, so a dual-licensed package is listed once under its full expression and both
 * options stay visible.
 */
export function licenseHeading(license) {
  return license
    .replaceAll(/[()]/g, '')
    .trim()
    .split(EXPRESSION_OPERATOR)
    .map((part, index) => (index % 2 === 0 ? appendixName(part.trim()) : part.trim()))
    .join(' ');
}

/** The individual SPDX ids of a declared license, which the appendix must cover one by one. */
export function licenseIds(license) {
  return license
    .replaceAll(/[()]/g, '')
    .trim()
    .split(EXPRESSION_OPERATOR)
    .filter((_, index) => index % 2 === 0)
    .map((id) => id.trim());
}

/**
 * `homepage`, else the repository URL, normalised to something a reader can open: npm manifests
 * carry `git+`-prefixed clone URLs, the `git://` protocol GitHub retired, and the `github:owner/repo`
 * and bare `owner/repo` shorthands.
 */
function projectUrl(source) {
  const repository = typeof source.repository === 'string' ? source.repository : source.repository?.url;
  const url = source.homepage ?? repository;
  if (!url) return null;
  return url
    .replace(/^git\+/, '')
    .replace(/^git:\/\//, 'https://')
    .replace(/^(?:github:)?([\w.-]+\/[\w.-]+)$/, 'https://github.com/$1')
    .replace(/\.git$/, '');
}

function declaredLicense(source) {
  const license =
    typeof source.license === 'string' ? source.license : (source.license?.type ?? source.licenses?.[0]?.type);
  return license?.trim() || null;
}

/**
 * Extracts attribution lines from license/notice file contents: lines opening with `copyright` that
 * either carry a year or a `(c)`/`©` marker, or name a holder outright, so neither prose such as
 * "copyright notice" nor a license's own numbered clauses and warranty boilerplate are mistaken for
 * an attribution. Lines naming no holder at all, and a license template's `<year>` / `[yyyy]`
 * placeholders, are dropped too.
 *
 * Set `fromLicenseText` when the contents are license texts rather than NOTICE files: a license's
 * steward holds the copyright on the license, not on the dependency quoting it.
 */
export function copyrightLines(fileContents, fromLicenseText) {
  const lines = fileContents
    .flatMap((content) => content.split('\n'))
    .map((line) => line.trim())
    .filter(
      (line) =>
        COPYRIGHT_LINE.test(line) &&
        (NAMES_A_HOLDER.test(line) || HOLDER_NAME.test(line.replace(COPYRIGHT_MARKER, ''))),
    )
    .filter((line) => !TEMPLATE_BRACKETS.test(line) && !TEMPLATE_PHRASE.test(line))
    .filter((line) => line.replace(COPYRIGHT_MARKER, '').trim() !== '')
    .filter((line) => !(fromLicenseText && LICENSE_STEWARD.test(line)));
  return [...new Set(lines)].slice(0, MAX_COPYRIGHT_LINES);
}

function isDirectory(dir) {
  return fs.statSync(dir, { throwIfNoEntry: false })?.isDirectory() ?? false;
}

/**
 * The paths of the license and notice files a package ships, sorted so the output does not depend
 * on listing order.
 */
function legalFilesIn(dir) {
  if (!dir) return [];
  return fs
    .readdirSync(dir)
    .filter((name) => LEGAL_FILE_NAME.test(name))
    .map((name) => path.join(dir, name))
    // CWE-59: lstat, so a LICENSE or NOTICE that is a symlink is
    // never followed — a package must not make the inventory quote a file outside itself.
    .filter((file) => fs.lstatSync(file, { throwIfNoEntry: false })?.isFile() ?? false)
    .sort();
}

function readManifest(dir) {
  const manifest = path.join(dir, 'package.json');
  return fs.existsSync(manifest) ? JSON.parse(fs.readFileSync(manifest, 'utf8')) : null;
}

/** Attributions a package's own legal files carry, read from where `npm ci` unpacked it. */
function attributions(dir) {
  const legalFiles = legalFilesIn(dir);
  const noticeFiles = legalFiles.filter((file) => /^notice/i.test(path.basename(file)));
  // Attributions come from NOTICE files whenever the package ships one. A LICENSE file is the
  // license text, and its own headers attribute the license, not the dependency quoting it.
  const sources = noticeFiles.length > 0 ? noticeFiles : legalFiles;
  return copyrightLines(
    sources.map((file) => fs.readFileSync(file, 'utf8')),
    noticeFiles.length === 0,
  );
}

/**
 * The production dependency graph of `<rootDir>/package-lock.json`, sorted by `name@version`:
 * every entry except the root project and the `dev: true` ones, minus [isSkipped].
 *
 * The lock names and versions every package; its license, homepage and copyright lines come from
 * `<rootDir>/<lock path>` where `npm ci` unpacked it. Packages the lock restricts by `os` or `cpu`
 * are read from the lock alone, so they render identically on every platform — the lock knows they
 * are redistributed, but only the copy this machine happens to unpack knows their holders, and
 * reading it would make the CI gate fail on the platform it was not generated on.
 *
 * Fails rather than publishing an incomplete attribution: on a package whose lock entry and
 * manifest both declare no license and that has no [LICENSE_OVERRIDES] entry.
 */
export function collectPackages(rootDir) {
  const lockFile = path.join(rootDir, 'package-lock.json');
  if (!fs.existsSync(lockFile)) {
    throw new Error(`${lockFile} does not exist — the console's package-lock.json is the inventory's input.`);
  }
  const lock = JSON.parse(fs.readFileSync(lockFile, 'utf8'));
  const nodeModulesDir = path.resolve(rootDir, 'node_modules') + path.sep;
  // The realpath re-check below compares resolved paths, so its prefix must be resolved too — else a
  // checkout reached through a symlink (macOS /var -> /private/var) reads as an escape.
  const realNodeModulesDir = path.join(fs.realpathSync(rootDir), 'node_modules') + path.sep;
  const packages = new Map();
  const unlicensed = [];
  for (const [lockPath, entry] of Object.entries(lock.packages ?? {})) {
    if (lockPath === '' || entry.dev) continue;
    // CWE-22: the key names the directory read below, so it must resolve
    // inside node_modules — a crafted lock must not make the inventory read and quote other files.
    const packageDir = path.resolve(rootDir, lockPath);
    if (!packageDir.startsWith(nodeModulesDir)) {
      throw new Error(`Lock entry "${lockPath}" resolves outside node_modules — refusing to read it.`);
    }
    // realpath re-check: the lexical startsWith above passes for a directory symlink; re-verify
    // containment after resolving.
    let resolvedDir = packageDir;
    try {
      const real = fs.realpathSync(packageDir);
      if (!real.startsWith(realNodeModulesDir)) {
        throw new Error(`Lock entry "${lockPath}" symlinks outside node_modules — refusing to read it.`);
      }
      resolvedDir = real;
    } catch (error) {
      // Directory absent (uninstalled platform-optional package) — isDirectory() below returns false
      // and the entry falls through to the lock-only path. Any other error is a real failure.
      if (error.code !== 'ENOENT' && error.code !== 'ENOTDIR') throw error;
    }
    const name = lockPath.slice(lockPath.lastIndexOf(NODE_MODULES_SEGMENT) + NODE_MODULES_SEGMENT.length);
    if (isSkippedPath(lockPath)) continue;
    const key = `${name}@${entry.version}`;
    // npm lists one lock path per place it nests a package; the same name@version needs one bullet.
    if (packages.has(key)) continue;
    // A package `npm ci` installs only on some platforms is read from the lock alone: reading the
    // one copy this machine unpacked would make the region — and so the CI gate — platform-specific.
    const dir = entry.os || entry.cpu ? null : resolvedDir;
    const manifest = dir && isDirectory(dir) ? readManifest(dir) : null;
    // Through declaredLicense, not entry.license directly: npm carries the deprecated object and
    // array forms too, and a non-string there must fall back rather than throw.
    const license = declaredLicense(entry) || (manifest && declaredLicense(manifest)) || LICENSE_OVERRIDES[name];
    if (!license) {
      unlicensed.push(key);
      continue;
    }
    packages.set(key, {
      name,
      version: entry.version,
      license,
      projectUrl: manifest ? projectUrl(manifest) : resolvedGitUrl(entry),
      copyrightLines: manifest ? attributions(dir) : [],
    });
  }
  if (unlicensed.length > 0) {
    throw new Error(
      `No license declared for ${[...new Set(unlicensed)].sort().join(', ')} — read each package's own ` +
        'license file and add it to LICENSE_OVERRIDES, or drop the dependency.',
    );
  }
  return [...packages.values()].sort(byKey);
}

/** A lock entry's `resolved` when it is a git URL — the only project URL an uninstalled package has. */
function resolvedGitUrl(entry) {
  return /^git[+:]/.test(entry.resolved ?? '') ? projectUrl({ repository: entry.resolved }) : null;
}

/** Stable order for both the inventory and its per-license groups: `name@version`, byte order. */
function byKey(a, b) {
  const [left, right] = [`${a.name}@${a.version}`, `${b.name}@${b.version}`];
  return left < right ? -1 : left > right ? 1 : 0;
}

/** Renders the generated region body: packages grouped under their license heading. */
export function renderInventory(packages) {
  const groups = new Map();
  for (const pkg of packages) {
    const heading = licenseHeading(pkg.license);
    groups.set(heading, [...(groups.get(heading) ?? []), pkg]);
  }
  return [...groups.keys()]
    .sort()
    .map((heading) => {
      const bullets = groups
        .get(heading)
        .sort(byKey)
        .map((pkg) =>
          [
            `- **${pkg.name}** ${pkg.version} — ${pkg.projectUrl ?? 'no project URL'}`,
            ...pkg.copyrightLines.map((line) => `  - ${line}`),
          ].join('\n'),
        )
        .join('\n');
      return `#### ${heading}\n\n${bullets}`;
    })
    .join('\n\n');
}

/** Replaces the text between the `begin` and `end` marker lines of `document` with `body`. */
export function replaceRegion(document, begin, end, body) {
  // The body is built from dependency-supplied text, so a license file carrying a marker string
  // would otherwise move the region boundary on the next generate.
  if (body.includes(begin) || body.includes(end)) {
    throw new Error(
      `The generated body contains a region marker — a dependency's own legal files must not be able to ` +
        `move the '${begin}' / '${end}' boundary.`,
    );
  }
  const beginAt = document.indexOf(begin);
  if (beginAt < 0) {
    throw new Error(`Marker '${begin}' not found — the region markers must stay in the document verbatim.`);
  }
  const endAt = document.indexOf(end, beginAt + begin.length);
  if (endAt < 0) {
    throw new Error(`Marker '${end}' not found after '${begin}' — the region markers must stay in the document verbatim.`);
  }
  return `${document.slice(0, beginAt + begin.length)}\n\n${body.trim()}\n\n${document.slice(endAt)}`;
}

/**
 * The appendix names of `licenseIdsInUse` that have no `### <name>` heading in `document`'s
 * `## License texts` appendix. Scoped to the appendix: a `### ` line before it — a future section
 * heading, or one inside a quoted license text — reproduces no license text, so counting it would
 * pass coverage for a license the document never carries.
 */
export function licenseHeadingsMissing(document, licenseIdsInUse) {
  const lines = document.split('\n');
  const appendixAt = lines.findIndex((line) => line.trimEnd() === LICENSE_TEXTS_HEADING);
  const headings = new Set(
    (appendixAt < 0 ? [] : lines.slice(appendixAt))
      .filter((line) => line.startsWith('### '))
      .map((line) => line.slice('### '.length).trim()),
  );
  const missing = licenseIdsInUse.map(appendixName).filter((name) => !headings.has(name));
  return [...new Set(missing)].sort();
}

function renderedDocument() {
  const packages = collectPackages(CONSOLE_DIR);
  const committed = fs.readFileSync(INVENTORY_FILE, 'utf8');
  const document = replaceRegion(committed, REGION_BEGIN, REGION_END, renderInventory(packages));
  const missing = licenseHeadingsMissing(document, packages.flatMap((pkg) => licenseIds(pkg.license)));
  if (missing.length > 0) {
    throw new Error(
      `The '## License texts' appendix has no '### <name>' section for ${missing.join(', ')}. ` +
        'Add each one, copying the text from a license file on disk.',
    );
  }
  return { committed, document, count: packages.length };
}

function main() {
  const mode = process.argv[2];
  if (mode !== 'generate' && mode !== 'check') {
    console.error('Usage: third-party-licenses.mjs generate|check');
    process.exit(2);
  }
  let rendered;
  try {
    rendered = renderedDocument();
  } catch (error) {
    console.error(error.message);
    process.exit(1);
  }
  if (mode === 'generate') {
    fs.writeFileSync(INVENTORY_FILE, rendered.document);
    console.log(`Wrote ${rendered.count} console packages to ${INVENTORY_FILE}.`);
    return;
  }
  if (rendered.document !== rendered.committed) {
    console.error(
      `The console region of ${INVENTORY_FILE} is out of date — run npm run licenses:generate and commit the result.`,
    );
    process.exit(1);
  }
  console.log(`The console region of ${INVENTORY_FILE} matches the ${rendered.count} packages in package-lock.json.`);
}

if (import.meta.filename === path.resolve(process.argv[1] ?? '')) main();
