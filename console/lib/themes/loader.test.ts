import { describe, it, expect } from 'vitest';
import {
  BUILT_IN_THEMES,
  checkThemeEntry,
  missingThemeStylesheets,
  parseThemeEntries,
  parseThemeModes,
  resolveThemeEntries,
  toThemeEntry,
} from './loader';

describe('checkThemeEntry', () => {
  it.each([
    'brand-dark:/themes/brand-dark.css',
    'a:/themes/a.css',
    'acme:/themes/nested/acme.css',
    'my-theme-2:/themes/my-theme-2.css',
  ])('accepts %s', (entry) => {
    expect(checkThemeEntry(entry)).toBeNull();
  });

  describe('rejects anything that is not a same-origin path', () => {
    it.each([
      ['a remote https URL', 'acme:https://themes.example/acme.css'],
      ['a remote http URL', 'acme:http://localhost:8090/acme.css'],
      ['a protocol-relative URL that reads as local', 'acme://evil.example/acme.css'],
      ['a data URL', 'acme:data:text/css,body{}'],
      ['a blob URL', 'acme:blob:http://localhost/abc'],
      ['a scheme-relative path that reads as absolute', 'acme://themes/acme.css'],
    ])('%s: %s', (_label, entry) => {
      expect(checkThemeEntry(entry)).toMatch(/same-origin path/);
    });

    it.each([
      ['a parent-relative path', 'acme:../secret.css'],
      ['traversal inside a valid-looking path', 'acme:/themes/../../etc/passwd.css'],
    ])('%s: %s', (_label, entry) => {
      expect(checkThemeEntry(entry)).toMatch(/\.\./);
    });

    it.each([
      ['a non-css extension', 'acme:/themes/acme.js'],
      ['no extension', 'acme:/themes/acme'],
      ['a query string', 'acme:/themes/acme.css?v=1'],
      ['a relative path', 'acme:themes/acme.css'],
      ['the empty path', 'acme:'],
    ])('%s: %s', (_label, entry) => {
      expect(checkThemeEntry(entry)).toMatch(/path must match/);
    });
  });

  describe('rejects an unusable name', () => {
    it.each([
      ['an uppercase name', 'Acme:/themes/acme.css'],
      ['a name with an underscore', 'acme_theme:/themes/acme.css'],
      ['a name with a dot', 'acme.theme:/themes/acme.css'],
      ['a name with a slash', 'acme/theme:/themes/acme.css'],
      ['an empty name', ':/themes/acme.css'],
      ['a whitespace name', ' acme:/themes/acme.css'],
      ['no separator at all', '/themes/acme.css'],
      ['the empty string', ''],
    ])('%s: %s', (_label, entry) => {
      expect(checkThemeEntry(entry)).toMatch(/name must match/);
    });
  });

  it.each(BUILT_IN_THEMES)('rejects the built-in name %s', (name) => {
    expect(checkThemeEntry(`${name}:/themes/${name}.css`)).toMatch(/built-in/);
  });

  it('splits at the first colon so a name never swallows the path', () => {
    expect(checkThemeEntry('acme:/themes:weird.css')).toMatch(/path must match/);
  });
});

describe('parseThemeEntries', () => {
  it('returns nothing for missing or blank input', () => {
    expect(parseThemeEntries(undefined)).toEqual({ entries: [], rejected: [] });
    expect(parseThemeEntries(null)).toEqual({ entries: [], rejected: [] });
    expect(parseThemeEntries('')).toEqual({ entries: [], rejected: [] });
    expect(parseThemeEntries(' , ,, ')).toEqual({ entries: [], rejected: [] });
  });

  it('splits and trims the raw CONSOLE_THEMES value', () => {
    expect(parseThemeEntries(' acme:/themes/acme.css , acme-dark:/themes/dark.css ')).toEqual({
      entries: [
        { name: 'acme', path: '/themes/acme.css' },
        { name: 'acme-dark', path: '/themes/dark.css' },
      ],
      rejected: [],
    });
  });
});

describe('parseThemeModes', () => {
  const names = ['acme', 'acme-dark', 'corp-light'];

  it('returns no pair and no reason for missing or blank input', () => {
    expect(parseThemeModes(undefined, names)).toEqual({ modes: null, rejected: null });
    expect(parseThemeModes(null, names)).toEqual({ modes: null, rejected: null });
    expect(parseThemeModes('', names)).toEqual({ modes: null, rejected: null });
    expect(parseThemeModes(' , ,, ', names)).toEqual({ modes: null, rejected: null });
  });

  it('accepts a light/dark pair of configured names', () => {
    expect(parseThemeModes('light:acme,dark:acme-dark', names)).toEqual({
      modes: { light: 'acme', dark: 'acme-dark' },
      rejected: null,
    });
  });

  it('accepts the pair in either order and trims the segments', () => {
    expect(parseThemeModes(' dark:acme-dark , light:acme ', names)).toEqual({
      modes: { light: 'acme', dark: 'acme-dark' },
      rejected: null,
    });
  });

  it('names the same theme on both sides', () => {
    expect(parseThemeModes('light:acme,dark:acme', names)).toEqual({
      modes: { light: 'acme', dark: 'acme' },
      rejected: null,
    });
  });

  describe('ignores the setting as a whole', () => {
    it.each([
      ['only one side named', 'light:acme'],
      ['only the dark side named', 'dark:acme-dark'],
      ['no mode prefix', 'acme,acme-dark'],
      ['an unknown mode', 'midnight:acme,dark:acme-dark'],
      ['a missing light name', 'light:,dark:acme-dark'],
      ['a missing dark name', 'light:acme,dark:'],
      ['a trailing segment', 'light:acme,dark:acme-dark,extra'],
      ['a duplicate mode', 'light:acme,light:corp-light,dark:acme-dark'],
      ['an unknown theme name', 'light:nope,dark:acme-dark'],
      ['a name that is not configured', 'light:acme-extra,dark:acme-dark'],
      ['a built-in name', 'light:light,dark:dark'],
      ['the built-in follower', 'light:system,dark:acme-dark'],
      ['a path where a name belongs', 'light:/themes/acme.css,dark:acme-dark'],
    ])('%s: %s', (_label, raw) => {
      const { modes, rejected } = parseThemeModes(raw, names);
      expect(modes).toBeNull();
      expect(rejected).toBeTypeOf('string');
      expect(rejected).toContain(raw);
    });

    it('reports one reason for the whole value, not one per segment', () => {
      const { rejected } = parseThemeModes('light:nope,dark:also-nope', names);
      expect(rejected).toBe(
        'light:nope,dark:also-nope: "nope" is not a CONSOLE_THEMES entry',
      );
    });
  });
});

describe('resolveThemeEntries', () => {
  it('validates entries that were already split', () => {
    expect(
      resolveThemeEntries(['acme:/themes/acme.css', 'acme-dark:/themes/dark.css']),
    ).toEqual({
      entries: [
        { name: 'acme', path: '/themes/acme.css' },
        { name: 'acme-dark', path: '/themes/dark.css' },
      ],
      rejected: [],
    });
  });

  it('reports a reason for each rejected entry and keeps the rest', () => {
    const { entries, rejected } = resolveThemeEntries([
      'acme:/themes/acme.css',
      'https://evil.example/x.css',
      'dark:/themes/dark.css',
      'acme:/themes/other.css',
    ]);

    expect(entries).toEqual([{ name: 'acme', path: '/themes/acme.css' }]);
    expect(rejected).toHaveLength(3);
    expect(rejected[0]).toMatch(/https:\/\/evil.example\/x.css/);
    expect(rejected[1]).toMatch(/built-in/);
    expect(rejected[2]).toMatch(/already defined/);
  });

  it('keeps the first entry when a name repeats', () => {
    const { entries } = resolveThemeEntries(['acme:/themes/a.css', 'acme:/themes/b.css']);
    expect(entries).toEqual([{ name: 'acme', path: '/themes/a.css' }]);
  });
});

describe('toThemeEntry', () => {
  it('splits an entry that already passed validation', () => {
    expect(toThemeEntry('acme:/themes/acme.css')).toEqual({
      name: 'acme',
      path: '/themes/acme.css',
    });
  });
});

describe('missingThemeStylesheets', () => {
  const entries = [
    { name: 'acme', path: '/themes/acme.css' },
    { name: 'acme-dark', path: '/themes/dark.css' },
  ];

  it('returns the paths absent from the loaded stylesheets', () => {
    expect(missingThemeStylesheets(entries, ['/themes/acme.css'])).toEqual(['/themes/dark.css']);
  });

  it('matches a fully resolved href against the configured path', () => {
    expect(
      missingThemeStylesheets(entries, [
        'http://localhost:3000/themes/acme.css',
        'http://localhost:3000/themes/dark.css',
      ]),
    ).toEqual([]);
  });

  it('reports every path when nothing loaded', () => {
    expect(missingThemeStylesheets(entries, [])).toEqual([
      '/themes/acme.css',
      '/themes/dark.css',
    ]);
  });

  it('reports a shared path once', () => {
    const shared = [
      { name: 'a', path: '/themes/shared.css' },
      { name: 'b', path: '/themes/shared.css' },
    ];
    expect(missingThemeStylesheets(shared, [])).toEqual(['/themes/shared.css']);
  });

  it('does not let an unrelated sheet whose href ends in the path stand in for the theme', () => {
    expect(
      missingThemeStylesheets(
        [{ name: 'acme', path: '/themes/acme.css' }],
        ['http://localhost:3000/plugins/sneaky/themes/acme.css'],
      ),
    ).toEqual(['/themes/acme.css']);
  });

  it('matches a resolved same-origin URL even against a relative-path entry', () => {
    expect(
      missingThemeStylesheets(
        [{ name: 'acme', path: '/themes/acme.css' }],
        ['https://console.example/themes/acme.css'],
        'https://console.example/app/',
      ),
    ).toEqual([]);
  });

  it('stays missing when the loaded href resolves on a foreign origin', () => {
    expect(
      missingThemeStylesheets(
        [{ name: 'acme', path: '/themes/acme.css' }],
        ['https://evil.example/themes/acme.css'],
        'https://console.example/app/',
      ),
    ).toEqual(['/themes/acme.css']);
  });
});
