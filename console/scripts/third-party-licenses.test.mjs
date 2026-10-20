import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';

import {
  collectPackages,
  copyrightLines,
  licenseHeading,
  licenseHeadingsMissing,
  licenseIds,
  renderInventory,
  replaceRegion,
} from './third-party-licenses.mjs';

const tempRoots = [];

// The copyright-line rule exists twice, here and in app/buildSrc/src/main/kotlin/ThirdPartyNotices.kt.
// This file is the shared truth table both suites assert against, so a change to one generator's
// filter that the other does not follow fails whichever side drifted.
const COPYRIGHT_CASES_FILE = path.join(import.meta.dirname, '..', '..', 'app', 'config', 'third-party-copyright-cases.json');

/**
 * A console-shaped temp dir: `package-lock.json` holding `lockPackages`, plus the subset of
 * `node_modules` that `installed` describes (a package in the lock but not here stands in for a
 * platform-optional dependency this machine skipped). `files` maps a package-relative path to its
 * content.
 */
function consoleDir(lockPackages, installed = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'homer-licenses-'));
  tempRoots.push(root);
  fs.writeFileSync(
    path.join(root, 'package-lock.json'),
    JSON.stringify({ lockfileVersion: 3, packages: { '': { name: 'homer-console' }, ...lockPackages } }),
  );
  for (const [lockPath, { files = {}, ...manifest }] of Object.entries(installed)) {
    const dir = path.join(root, lockPath);
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, 'package.json'), JSON.stringify(manifest));
    for (const [name, content] of Object.entries(files)) {
      fs.writeFileSync(path.join(dir, name), content);
    }
  }
  return root;
}

afterEach(() => {
  while (tempRoots.length > 0) fs.rmSync(tempRoots.pop(), { recursive: true, force: true });
});

describe('collectPackages', () => {
  const lockPackages = {
    'node_modules/left': { version: '1.0.0', license: 'MIT' },
    'node_modules/dual': { version: '2.3.4', license: '(MPL-2.0 OR Apache-2.0)' },
    'node_modules/left/node_modules/@scope/nested': { version: '0.1.0', license: 'MIT' },
    'node_modules/only-in-lock': { version: '4.5.6', license: 'ISC' },
    'node_modules/toolchain': { version: '7.0.0', license: 'MIT', dev: true },
    'node_modules/@img/skipped': { version: '9.9.9', license: 'LGPL-3.0-or-later' },
    'node_modules/sharp': { version: '0.35.0', license: 'Apache-2.0' },
  };
  const installed = {
    'node_modules/left': {
      name: 'left',
      version: '1.0.0',
      homepage: 'https://example.test/left',
      files: { LICENSE: 'MIT License\n\nCopyright (c) 2019 Left Authors\n\nPermission…\n' },
    },
    'node_modules/dual': {
      name: 'dual',
      version: '2.3.4',
      repository: { type: 'git', url: 'git+https://github.com/example/dual.git' },
    },
    'node_modules/left/node_modules/@scope/nested': {
      name: '@scope/nested',
      version: '0.1.0',
      repository: 'github:example/nested',
    },
    'node_modules/toolchain': { name: 'toolchain', version: '7.0.0' },
  };

  it('reads the production graph, naming each package by its last lock path segment', () => {
    const packages = collectPackages(consoleDir(lockPackages, installed));

    expect(packages).toEqual([
      {
        name: '@scope/nested',
        version: '0.1.0',
        license: 'MIT',
        projectUrl: 'https://github.com/example/nested',
        copyrightLines: [],
      },
      {
        name: 'dual',
        version: '2.3.4',
        license: '(MPL-2.0 OR Apache-2.0)',
        projectUrl: 'https://github.com/example/dual',
        copyrightLines: [],
      },
      {
        name: 'left',
        version: '1.0.0',
        license: 'MIT',
        projectUrl: 'https://example.test/left',
        copyrightLines: ['Copyright (c) 2019 Left Authors'],
      },
      {
        name: 'only-in-lock',
        version: '4.5.6',
        license: 'ISC',
        projectUrl: null,
        copyrightLines: [],
      },
    ]);
  });

  it('skips every package nested under a pruned one, installed or not', () => {
    const nestedUnderPruned = {
      'node_modules/kept': { version: '1.0.0', license: 'MIT' },
      'node_modules/@img/sharp-wasm32': { version: '0.35.5', license: 'Apache-2.0 AND LGPL-3.0-or-later', optional: true },
      'node_modules/@img/sharp-wasm32/node_modules/@emnapi/runtime': { version: '1.11.3', license: 'MIT', optional: true },
      'node_modules/sharp/node_modules/semver': { version: '7.0.0', license: 'ISC' },
    };
    // Installed: an npm that unpacks the optional subtree must not change the inventory.
    const unpacked = {
      'node_modules/@img/sharp-wasm32/node_modules/@emnapi/runtime': {
        name: '@emnapi/runtime',
        version: '1.11.3',
        homepage: 'https://example.test/emnapi',
      },
    };

    for (const installedHere of [{}, unpacked]) {
      expect(collectPackages(consoleDir(nestedUnderPruned, installedHere)).map((p) => p.name)).toEqual(['kept']);
    }
  });

  it('takes the license from the installed manifest when the lock entry has none', () => {
    const root = consoleDir(
      { 'node_modules/quiet': { version: '1.0.0' } },
      { 'node_modules/quiet': { name: 'quiet', version: '1.0.0', license: 'BSD-2-Clause' } },
    );

    expect(collectPackages(root)[0].license).toBe('BSD-2-Clause');
  });

  it('resolves a license from the override map', () => {
    const root = consoleDir({ 'node_modules/khroma': { version: '2.1.0' } });

    expect(collectPackages(root)[0].license).toBe('MIT');
  });

  it('fails on a package with no license anywhere and no override', () => {
    const root = consoleDir({ 'node_modules/mystery': { version: '1.0.0' } });

    expect(() => collectPackages(root)).toThrow(/mystery@1\.0\.0/);
  });

  it('uses a git resolved URL for a package the lock knows but this platform did not install', () => {
    const root = consoleDir({
      'node_modules/forked': { version: '1.0.0', license: 'MIT', resolved: 'git+https://github.com/example/forked.git' },
      'node_modules/tarballed': { version: '1.0.0', license: 'MIT', resolved: 'https://registry.npmjs.org/t.tgz' },
    });

    expect(collectPackages(root).map((p) => p.projectUrl)).toEqual(['https://github.com/example/forked', null]);
  });

  it('ignores the unpacked copy of a platform-specific package', () => {
    const root = consoleDir(
      { 'node_modules/@next/swc-here': { version: '1.0.0', license: 'MIT', os: ['darwin'], cpu: ['arm64'] } },
      {
        'node_modules/@next/swc-here': {
          name: '@next/swc-here',
          version: '1.0.0',
          homepage: 'https://example.test/swc',
          files: { LICENSE: 'Copyright (c) 2025 Platform Inc.\n' },
        },
      },
    );

    expect(collectPackages(root)).toEqual([
      {
        name: '@next/swc-here',
        version: '1.0.0',
        license: 'MIT',
        projectUrl: null,
        copyrightLines: [],
      },
    ]);
  });

  it('normalises the repository shorthands npm manifests use', () => {
    const root = consoleDir(
      {
        'node_modules/shorthand': { version: '1.0.0', license: 'MIT' },
        'node_modules/gitproto': { version: '1.0.0', license: 'MIT' },
      },
      {
        'node_modules/shorthand': { name: 'shorthand', version: '1.0.0', repository: 'example/shorthand' },
        'node_modules/gitproto': {
          name: 'gitproto',
          version: '1.0.0',
          repository: { type: 'git', url: 'git://github.com/example/gitproto.git' },
        },
      },
    );

    const byName = Object.fromEntries(collectPackages(root).map((p) => [p.name, p.projectUrl]));

    expect(byName).toEqual({
      shorthand: 'https://github.com/example/shorthand',
      gitproto: 'https://github.com/example/gitproto',
    });
  });

  it('prefers NOTICE lines over the license text and drops steward and template lines', () => {
    const root = consoleDir(
      {
        'node_modules/noticed': { version: '1.0.0', license: 'Apache-2.0' },
        'node_modules/stewarded': { version: '1.0.0', license: 'GPL-2.0-only' },
      },
      {
        'node_modules/noticed': {
          name: 'noticed',
          version: '1.0.0',
          files: {
            NOTICE: 'Copyright 2020 Noticed Inc.\n',
            LICENSE: 'Copyright (C) 1989 Free Software Foundation, Inc.\nCopyright (c) <year> <name of author>\n',
          },
        },
        'node_modules/stewarded': {
          name: 'stewarded',
          version: '1.0.0',
          files: { COPYING: 'Copyright (C) 1989 Free Software Foundation, Inc.\ncopyright notice\n' },
        },
      },
    );

    const byName = Object.fromEntries(collectPackages(root).map((p) => [p.name, p.copyrightLines]));

    expect(byName).toEqual({ noticed: ['Copyright 2020 Noticed Inc.'], stewarded: [] });
  });

  it('keeps an undated holder name but not boilerplate or a bracket template', () => {
    const root = consoleDir(
      { 'node_modules/holder': { version: '1.0.0', license: 'ISC' } },
      {
        'node_modules/holder': {
          name: 'holder',
          version: '1.0.0',
          files: {
            LICENSE: [
              'Copyright Eemeli Aro <eemeli@gmail.com>',
              'Copyright [2018] [Ohad Shai]',
              'Copyright <YEAR> <COPYRIGHT HOLDER>',
              'Copyright [yyyy] [name of copyright owner]',
              'Copyright {yyyy} Foo',
              'COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM',
              '',
            ].join('\n'),
          },
        },
      },
    );

    expect(collectPackages(root)[0].copyrightLines).toEqual([
      'Copyright Eemeli Aro <eemeli@gmail.com>',
      'Copyright [2018] [Ohad Shai]',
    ]);
  });

  it("drops the GPL's how-to-apply placeholder whatever its case", () => {
    const root = consoleDir(
      { 'node_modules/gpl': { version: '1.0.0', license: 'GPL-2.0-only' } },
      {
        'node_modules/gpl': {
          name: 'gpl',
          version: '1.0.0',
          files: {
            NOTICE: [
              'Copyright (C) YEAR NAME OF AUTHOR',
              'Copyright (C) Year Name Of Author',
              'Copyright (C) 2001 Year One Ltd',
              '',
            ].join('\n'),
          },
        },
      },
    );

    expect(collectPackages(root)[0].copyrightLines).toEqual(['Copyright (C) 2001 Year One Ltd']);
  });

  it('falls back when a lock entry declares its license in the deprecated object form', () => {
    const root = consoleDir({
      'node_modules/objectish': { version: '1.0.0', license: { type: 'MIT', url: 'https://example.test/mit' } },
      'node_modules/arrayish': { version: '1.0.0', licenses: [{ type: 'ISC' }] },
    });

    expect(Object.fromEntries(collectPackages(root).map((p) => [p.name, p.license]))).toEqual({
      objectish: 'MIT',
      arrayish: 'ISC',
    });
  });

  it('fails when there is no package-lock.json to read', () => {
    expect(() => collectPackages(path.join(os.tmpdir(), 'homer-licenses-absent'))).toThrow(
      /package-lock\.json does not exist/,
    );
  });

  // CWE-22: a lock key names the directory the inventory reads.
  it.each([
    ['a parent-directory key', '../outside'],
    ['a key escaping through node_modules', 'node_modules/../../outside'],
    ['an absolute key', path.join(os.tmpdir(), 'outside')],
  ])('path_traversal: refuses %s instead of reading it', (_, lockPath) => {
    const root = consoleDir({ [lockPath]: { version: '1.0.0', license: 'MIT' } });
    expect(() => collectPackages(root)).toThrow(/resolves outside node_modules/);
  });

  // CWE-22: a lock key's lexical path can be inside node_modules while the
  // directory it names is a symlink escaping it — only a realpath check catches that.
  it('refuses a package directory symlinked outside node_modules', () => {
    const root = consoleDir({ 'node_modules/evil': { version: '1.0.0', license: 'MIT' } });
    const outside = path.join(root, 'outside');
    fs.mkdirSync(outside);
    fs.writeFileSync(path.join(outside, 'package.json'), JSON.stringify({ name: 'evil', version: '1.0.0' }));
    fs.mkdirSync(path.join(root, 'node_modules'), { recursive: true });
    fs.symlinkSync(outside, path.join(root, 'node_modules', 'evil'), 'dir');

    expect(() => collectPackages(root)).toThrow(/symlinks outside node_modules/);
  });

  // The realpath re-check must compare against the resolved root: a checkout reached through a
  // symlinked path (macOS os.tmpdir() under /var -> /private/var) is not an escape.
  it('reads a console dir reached through a symlinked path', () => {
    const root = consoleDir(
      { 'node_modules/left': { version: '1.0.0', license: 'MIT' } },
      { 'node_modules/left': { name: 'left', version: '1.0.0' } },
    );
    const link = `${root}-link`;
    fs.symlinkSync(root, link, 'dir');
    tempRoots.push(link);

    expect(collectPackages(link).map((p) => p.name)).toEqual(['left']);
  });

  // CWE-59: a package's legal files are read by name.
  it('information_disclosure: does not follow a NOTICE symlink out of the package', () => {
    const root = consoleDir(
      { 'node_modules/sneaky': { version: '1.0.0', license: 'MIT' } },
      { 'node_modules/sneaky': { name: 'sneaky', files: { LICENSE: 'Copyright (c) 2020 Sneaky Authors' } } },
    );
    const secret = path.join(root, 'secret.txt');
    fs.writeFileSync(secret, 'Copyright (c) 1999 Leaked Holder\n');
    fs.symlinkSync(secret, path.join(root, 'node_modules', 'sneaky', 'NOTICE'));

    expect(collectPackages(root)[0].copyrightLines).toEqual(['Copyright (c) 2020 Sneaky Authors']);
  });
});

describe('copyrightLines', () => {
  it('agrees with the shared cross-generator cases', () => {
    const cases = JSON.parse(fs.readFileSync(COPYRIGHT_CASES_FILE, 'utf8'));

    expect(cases.length).toBeGreaterThanOrEqual(16);
    for (const { line, fromNotice, keep } of cases) {
      expect(copyrightLines([line], !fromNotice), `case '${line}' (fromNotice=${fromNotice})`).toEqual(
        keep ? [line] : [],
      );
    }
  });
});

describe('licenseHeading', () => {
  it('maps SPDX ids onto the appendix names the backend region already uses', () => {
    expect(licenseHeading('Apache-2.0')).toBe('Apache License, Version 2.0');
    expect(licenseHeading('MIT')).toBe('MIT License');
    expect(licenseHeading('BSD-3-Clause')).toBe('The 3-Clause BSD License');
    expect(licenseHeading('MIT-0')).toBe('MIT-0');
  });

  it('maps every appendix heading a console dependency could land under', () => {
    // A missing mapping would demand a second, duplicate section for a text the appendix has.
    expect(licenseHeading('EPL-1.0')).toBe('Eclipse Public License - v 1.0');
    expect(licenseHeading('EPL-2.0')).toBe('Eclipse Public License - v 2.0');
    expect(licenseHeading('LGPL-2.1')).toBe('GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1');
    expect(licenseHeading('LGPL-2.1-only')).toBe('GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1');
    expect(licenseHeading('GPL-2.0')).toBe('GNU GENERAL PUBLIC LICENSE, Version 2');
    expect(licenseHeading('GPL-2.0-only')).toBe('GNU GENERAL PUBLIC LICENSE, Version 2');
    expect(licenseHeading('GPL-2.0-with-classpath-exception')).toBe(
      'GNU GENERAL PUBLIC LICENSE, Version 2 + Classpath Exception',
    );
    expect(licenseHeading('CDDL-1.0')).toBe('COMMON DEVELOPMENT AND DISTRIBUTION LICENSE (CDDL) Version 1.0');
  });

  it('uses an unmapped id verbatim', () => {
    expect(licenseHeading('ISC')).toBe('ISC');
    expect(licenseHeading('CC-BY-4.0')).toBe('CC-BY-4.0');
  });

  it('normalises an expression into one heading, keeping the operator', () => {
    expect(licenseHeading('(MPL-2.0 OR Apache-2.0)')).toBe('MPL-2.0 OR Apache License, Version 2.0');
    expect(licenseHeading('(MIT AND BSD-3-Clause)')).toBe('MIT License AND The 3-Clause BSD License');
  });
});

describe('licenseIds', () => {
  it('splits an expression into the individual ids the appendix must cover', () => {
    expect(licenseIds('(MPL-2.0 OR Apache-2.0)')).toEqual(['MPL-2.0', 'Apache-2.0']);
    expect(licenseIds('ISC')).toEqual(['ISC']);
  });
});

describe('renderInventory', () => {
  it('groups by license heading, sorts, and indents copyright lines', () => {
    const body = renderInventory([
      { name: 'zeta', version: '1.0.0', license: 'ISC', projectUrl: null, copyrightLines: [] },
      {
        name: 'alpha',
        version: '2.0.0',
        license: 'MIT',
        projectUrl: 'https://example.test/alpha',
        copyrightLines: ['Copyright (c) 2020 Alpha'],
      },
      { name: 'beta', version: '3.0.0', license: '(MPL-2.0 OR Apache-2.0)', projectUrl: null, copyrightLines: [] },
      { name: 'aardvark', version: '1.0.0', license: 'MIT', projectUrl: null, copyrightLines: [] },
    ]);

    expect(body).toBe(
      [
        '#### ISC',
        '',
        '- **zeta** 1.0.0 — no project URL',
        '',
        '#### MIT License',
        '',
        '- **aardvark** 1.0.0 — no project URL',
        '- **alpha** 2.0.0 — https://example.test/alpha',
        '  - Copyright (c) 2020 Alpha',
        '',
        '#### MPL-2.0 OR Apache License, Version 2.0',
        '',
        '- **beta** 3.0.0 — no project URL',
      ].join('\n'),
    );
  });
});

describe('replaceRegion', () => {
  const begin = '<!-- BEGIN GENERATED: console (npm run licenses:generate) -->';
  const end = '<!-- END GENERATED: console -->';

  it('keeps the markers verbatim with one blank line around the body', () => {
    const document = `## Homer console\n\n${begin}\n\n_placeholder_\n\n${end}\n\n## License texts\n`;

    expect(replaceRegion(document, begin, end, '#### ISC\n')).toBe(
      `## Homer console\n\n${begin}\n\n#### ISC\n\n${end}\n\n## License texts\n`,
    );
  });

  it('fails when a marker is missing', () => {
    expect(() => replaceRegion('no markers here', begin, end, 'body')).toThrow(/BEGIN GENERATED: console/);
  });

  it('refuses a body carrying either marker', () => {
    const document = `${begin}\n\nstale\n\n${end}\n`;

    expect(() => replaceRegion(document, begin, end, `body\n${begin}\nforged`)).toThrow(/region marker/);
    expect(() => replaceRegion(document, begin, end, `body\n${end}\nforged`)).toThrow(/region marker/);
  });
});

describe('licenseHeadingsMissing', () => {
  it('reports the appendix names with no "### " heading', () => {
    const document = '## License texts\n\n### MIT License\n\ntext\n\n### ISC\n\ntext\n';

    expect(licenseHeadingsMissing(document, ['MIT', 'ISC', 'MPL-2.0'])).toEqual(['MPL-2.0']);
  });

  it('ignores a heading before the appendix', () => {
    const document = '### MIT License\n\nquoted above the appendix\n\n## License texts\n\n### ISC\n\ntext\n';

    expect(licenseHeadingsMissing(document, ['MIT', 'ISC'])).toEqual(['MIT License']);
  });
});
