import { describe, it, expect, afterEach, vi } from 'vitest';

/**
 * `appConfig` is resolved once at module import, so each case has to reset the
 * module registry and re-import after setting the global up.
 *
 * Scope is deliberately narrow: the runtime-config branches for operator-supplied
 * plugin and theme entries. The injected global is the only client-side source:
 * a browser bundle never sees `CONSOLE_*` env vars, so if the client stopped
 * reading `pluginEntries`/`themeEntries` from it, every deployment, dev
 * included, would quietly load no plugins and no themes at all.
 */
type Global = typeof globalThis & { __HOMER_CONFIG__?: Record<string, unknown> };

async function loadConfig() {
  vi.resetModules();
  const mod = await import('./app-config');
  return mod.appConfig;
}

afterEach(() => {
  delete (globalThis as Global).__HOMER_CONFIG__;
  vi.unstubAllEnvs();
});

describe('appConfig.plugins.entries', () => {
  it('defaults to empty when nothing is configured', async () => {
    expect((await loadConfig()).plugins.entries).toEqual([]);
  });

  it('reads the injected runtime config', async () => {
    (globalThis as Global).__HOMER_CONFIG__ = {
      apiBaseUrl: null,
      authEnabled: false,
      pluginEntries: ['/plugins/a.js', '/plugins/b.js'],
    };
    expect((await loadConfig()).plugins.entries).toEqual(['/plugins/a.js', '/plugins/b.js']);
  });

  it('stays empty when the runtime config carries no plugins', async () => {
    (globalThis as Global).__HOMER_CONFIG__ = { apiBaseUrl: null, authEnabled: false };
    expect((await loadConfig()).plugins.entries).toEqual([]);
  });

  it('ignores CONSOLE_PLUGINS in the client env', async () => {
    // Only the root layout reads the env, server-side; a browser bundle cannot.
    vi.stubEnv('CONSOLE_PLUGINS', '/plugins/from-env.js');
    expect((await loadConfig()).plugins.entries).toEqual([]);
  });
});

describe('appConfig.themes.modes', () => {
  it('is absent when no pair is configured', async () => {
    expect((await loadConfig()).themes.modes).toBeUndefined();
  });

  it('reads the injected runtime config', async () => {
    (globalThis as Global).__HOMER_CONFIG__ = {
      apiBaseUrl: null,
      authEnabled: false,
      themeModes: { light: 'acme', dark: 'acme-dark' },
    };
    expect((await loadConfig()).themes.modes).toEqual({ light: 'acme', dark: 'acme-dark' });
  });

  it('ignores CONSOLE_THEME_MODES in the client env', async () => {
    vi.stubEnv('CONSOLE_THEME_MODES', 'light:acme,dark:acme-dark');
    expect((await loadConfig()).themes.modes).toBeUndefined();
  });
});

describe('appConfig.themes.entries', () => {
  it('defaults to empty when nothing is configured', async () => {
    expect((await loadConfig()).themes.entries).toEqual([]);
  });

  it('reads the injected runtime config', async () => {
    (globalThis as Global).__HOMER_CONFIG__ = {
      apiBaseUrl: null,
      authEnabled: false,
      themeEntries: ['acme:/themes/acme.css'],
    };
    expect((await loadConfig()).themes.entries).toEqual(['acme:/themes/acme.css']);
  });

  it('stays empty when the runtime config carries no themes', async () => {
    (globalThis as Global).__HOMER_CONFIG__ = { apiBaseUrl: null, authEnabled: false };
    expect((await loadConfig()).themes.entries).toEqual([]);
  });

  it('ignores CONSOLE_THEMES in the client env', async () => {
    vi.stubEnv('CONSOLE_THEMES', 'from-env:/themes/env.css');
    expect((await loadConfig()).themes.entries).toEqual([]);
  });

  // `default.json` is operator-replaceable at image build time and `themes` is
  // an additive key, so a pre-themes copy must yield empty entries, not a throw.
  it('reads the injected themes on a themes-less default config', async () => {
    vi.resetModules();
    vi.doMock('@/config/default.json', () => ({
      default: {
        api: { baseUrl: 'http://localhost:8080', timeout: 30000, allowedOrigins: [] },
        auth: { enabled: false, mode: 'cookie', allowBearer: false, tokenKey: 'homer_auth_token' },
        polling: {},
        pagination: {},
        ui: { defaultTheme: 'dark' },
        plugins: { entries: [] },
      },
    }));
    try {
      (globalThis as Global).__HOMER_CONFIG__ = {
        apiBaseUrl: null,
        authEnabled: false,
        themeEntries: ['acme:/themes/acme.css'],
      };
      expect((await import('./app-config')).appConfig.themes.entries).toEqual([
        'acme:/themes/acme.css',
      ]);
    } finally {
      vi.doUnmock('@/config/default.json');
    }
  });

  it('stays empty on a themes-less default config with no runtime config', async () => {
    vi.resetModules();
    vi.doMock('@/config/default.json', () => ({ default: { plugins: { entries: [] } } }));
    try {
      expect((await import('./app-config')).appConfig.themes.entries).toEqual([]);
    } finally {
      vi.doUnmock('@/config/default.json');
    }
  });
});

describe('appConfig api and auth', () => {
  it('keeps the defaults when the runtime config sets none of them', async () => {
    (globalThis as Global).__HOMER_CONFIG__ = { apiBaseUrl: null, authEnabled: true, authMode: 'bogus' };
    const config = await loadConfig();
    expect(config.api.baseUrl).toBe('http://localhost:8080');
    expect(config.api.allowedOrigins).toEqual([]);
    expect(config.auth).toMatchObject({ enabled: true, mode: 'cookie', allowBearer: false });
  });

  it('applies the injected endpoint, auth mode, bearer switch and origin allowlist', async () => {
    (globalThis as Global).__HOMER_CONFIG__ = {
      apiBaseUrl: 'https://homer.example',
      authEnabled: true,
      authMode: 'bearer',
      allowBearer: true,
      allowedApiOrigins: ['https://homer.example'],
    };
    const config = await loadConfig();
    expect(config.api.baseUrl).toBe('https://homer.example');
    expect(config.api.allowedOrigins).toEqual(['https://homer.example']);
    expect(config.auth).toMatchObject({ enabled: true, mode: 'bearer', allowBearer: true });
  });
});
