import { readdirSync } from 'node:fs';
import path from 'node:path';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import {
  RESERVED_ROUTES,
  checkPluginEntry,
  loadPlugins,
  parsePluginEntries,
  toPluginEntry,
  validateManifest,
} from './loader';
import type { PluginManifest } from './types';

function manifest(over: Partial<PluginManifest> = {}): PluginManifest {
  return { route: '/demo', title: 'Demo', mount: () => undefined, ...over };
}

/**
 * Every first-level static route under `app/`, read off the filesystem so a new
 * page cannot ship without being reserved. Route groups `(x)` are transparent;
 * dynamic `[x]` and private `_x` folders do not claim a static path.
 */
function staticAppRoutes(): string[] {
  const routes = new Set<string>();
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (!entry.isDirectory()) continue;
      const name = entry.name;
      if (name.startsWith('(') && name.endsWith(')')) walk(path.join(dir, name));
      else if (!name.startsWith('[') && !name.startsWith('_')) routes.add(`/${name}`);
    }
  };
  walk(path.resolve(__dirname, '../../app'));
  return [...routes].sort();
}

describe('parsePluginEntries', () => {
  it('returns an empty list for missing or blank input', () => {
    expect(parsePluginEntries(undefined)).toEqual([]);
    expect(parsePluginEntries(null)).toEqual([]);
    expect(parsePluginEntries('')).toEqual([]);
    expect(parsePluginEntries(' , ,, ')).toEqual([]);
  });

  it('splits and trims a comma-separated list', () => {
    expect(parsePluginEntries(' /plugins/a.js , /plugins/b.js ')).toEqual([
      '/plugins/a.js',
      '/plugins/b.js',
    ]);
  });
});

describe('checkPluginEntry', () => {
  it.each(['/plugins/a.js', '/plugins/nested/a.mjs', '/a.js', '/plugins/my-plugin.v2.js'])(
    'accepts %s',
    (entry) => {
      expect(checkPluginEntry(entry)).toBeNull();
    },
  );

  describe('rejects anything that is not a same-origin path', () => {
    it.each([
      ['a remote https URL', 'https://plugins.example/p.js'],
      ['a remote http URL', 'http://localhost:8090/plugin.js'],
      ['a protocol-relative URL that reads as local', '//evil.example/p.js'],
      ['a data URL', 'data:text/javascript,void 0'],
      ['a blob URL', 'blob:http://localhost/abc'],
      ['a javascript URL', 'javascript:alert(1)'],
    ])('%s: %s', (_label, entry) => {
      expect(checkPluginEntry(entry)).toMatch(/same-origin path/);
    });

    it.each([
      ['a parent-relative path', '../secret.js'],
      ['traversal inside a valid-looking path', '/plugins/../../etc/passwd.js'],
      ['bare traversal', '/..%2fx.js'],
    ])('%s: %s', (_label, entry) => {
      expect(checkPluginEntry(entry)).not.toBeNull();
    });

    it.each([
      ['a relative filename', 'plugin.js'],
      ['a non-js extension', '/plugins/a.json'],
      ['no extension', '/plugins/a'],
      ['a query string', '/plugins/a.js?v=1'],
      ['the empty string', ''],
    ])('%s: %s', (_label, entry) => {
      expect(checkPluginEntry(entry)).not.toBeNull();
    });
  });
});

describe('plugin requirements in the entry', () => {
  it.each(['admin', 'read', 'run', 'control', 'edit', 'debug'])('accepts @%s', (req) => {
    expect(checkPluginEntry(`/plugins/a.js@${req}`)).toBeNull();
    expect(toPluginEntry(`/plugins/a.js@${req}`)).toEqual({ path: '/plugins/a.js', requires: req });
  });

  it('treats an entry with no requirement as open', () => {
    expect(toPluginEntry('/plugins/a.js')).toEqual({ path: '/plugins/a.js' });
  });

  it('rejects an unknown requirement rather than ignoring it', () => {
    // Silently dropping `@admn` would publish an admin-only plugin to everyone.
    expect(checkPluginEntry('/plugins/a.js@admn')).toMatch(/unknown requirement/);
    expect(checkPluginEntry('/plugins/a.js@')).toMatch(/unknown requirement/);
  });

  it('still validates the path in front of the requirement', () => {
    expect(checkPluginEntry('https://evil.example/p.js@admin')).toMatch(/same-origin path/);
    expect(checkPluginEntry('/plugins/../x.js@admin')).toMatch(/\.\./);
  });
});

describe('validateManifest', () => {
  it('accepts a well-formed manifest', () => {
    expect(validateManifest(manifest())).toBeNull();
  });

  it('ignores a `requires` in the manifest — the gate lives in operator config', () => {
    // A plugin author must not be able to widen or narrow their own gate; the
    // field is simply not part of the manifest contract.
    expect(validateManifest({ ...manifest(), requires: 'nonsense' })).toBeNull();
  });

  it.each([
    ['undefined', undefined],
    ['null', null],
    ['a string', 'nope'],
  ])('rejects %s as the default export', (_label, value) => {
    expect(validateManifest(value)).toMatch(/not an object/);
  });

  it.each([
    ['a missing leading slash', 'demo'],
    ['a nested path', '/a/b'],
    ['uppercase', '/Demo'],
    ['path traversal', '/../secret'],
    ['a leading dash', '/-demo'],
  ])('rejects %s in route', (_label, route) => {
    expect(validateManifest(manifest({ route }))).toMatch(/invalid route/);
  });

  it('rejects a route reserved by the console', () => {
    expect(validateManifest(manifest({ route: '/processes' }))).toMatch(/reserved/);
    expect(validateManifest(manifest({ route: '/login' }))).toMatch(/reserved/);
    expect(validateManifest(manifest({ route: '/runners' }))).toMatch(/reserved/);
  });

  it('finds the static console routes on disk', () => {
    expect(staticAppRoutes()).toEqual(expect.arrayContaining(['/login', '/processes']));
  });

  it.each(staticAppRoutes())('reserves the static console route %s', (route) => {
    expect(RESERVED_ROUTES).toContain(route);
  });

  it('rejects a blank title', () => {
    expect(validateManifest(manifest({ title: '   ' }))).toMatch(/missing title/);
  });

  it('rejects a non-function mount', () => {
    expect(validateManifest({ ...manifest(), mount: 'nope' })).toMatch(/mount is not a function/);
  });
});

describe('loadPlugins', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('loads valid modules and records the source path', async () => {
    const importer = vi.fn().mockResolvedValue({ default: manifest() });
    const loaded = await loadPlugins([{ path: '/plugins/a.js' }], importer);
    expect(loaded).toHaveLength(1);
    expect(loaded[0]).toMatchObject({
      route: '/demo',
      title: 'Demo',
      sourceUrl: '/plugins/a.js',
    });
  });

  it('carries the entry requirement onto the loaded plugin', async () => {
    const importer = vi.fn().mockResolvedValue({ default: manifest() });
    const loaded = await loadPlugins([{ path: '/plugins/a.js', requires: 'admin' }], importer);
    expect(loaded[0].requires).toBe('admin');
  });

  it('skips a module that fails to import without failing the rest', async () => {
    const importer = vi.fn(async (url: string) => {
      if (url === '/plugins/bad.js') throw new Error('404');
      return { default: manifest({ route: '/good', title: 'Good' }) };
    });
    const loaded = await loadPlugins(
      [{ path: '/plugins/bad.js' }, { path: '/plugins/good.js' }],
      importer,
    );
    expect(loaded.map((p) => p.route)).toEqual(['/good']);
  });

  it('skips a module whose manifest is invalid', async () => {
    const importer = vi.fn().mockResolvedValue({ default: { route: '/x' } });
    expect(await loadPlugins([{ path: '/plugins/a.js' }], importer)).toEqual([]);
  });

  it('skips a plugin claiming a reserved route and logs why', async () => {
    const importer = vi.fn().mockResolvedValue({ default: manifest({ route: '/runners' }) });
    expect(await loadPlugins([{ path: '/plugins/a.js' }], importer)).toEqual([]);
    expect(console.error).toHaveBeenCalledWith(
      '[plugins] ignoring /plugins/a.js: route /runners is reserved by the console',
    );
  });

  it('keeps the first plugin when two claim the same route', async () => {
    const importer = vi.fn(async (url: string) => ({
      default: manifest({ title: url.includes('first') ? 'First' : 'Second' }),
    }));
    const loaded = await loadPlugins(
      [{ path: '/plugins/first.js' }, { path: '/plugins/second.js' }],
      importer,
    );
    expect(loaded).toHaveLength(1);
    expect(loaded[0].title).toBe('First');
  });

  it('returns an empty list for no entries and never calls the importer', async () => {
    const importer = vi.fn();
    expect(await loadPlugins([], importer)).toEqual([]);
    expect(importer).not.toHaveBeenCalled();
  });
});
