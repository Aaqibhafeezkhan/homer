import { describe, it, expect, vi, afterEach } from 'vitest';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { renderToStaticMarkup } from 'react-dom/server';
import RootLayout from './layout';

const interMock = vi.hoisted(() =>
  vi.fn((options: { variable?: string }) => ({
    className: 'inter-font-family',
    variable: options.variable ? 'inter-font-variable' : undefined,
  })),
);

const providersMock = vi.hoisted(() =>
  vi.fn(({ children }: { children: React.ReactNode }) => children),
);

vi.mock('next/font/google', () => ({ Inter: interMock }));
vi.mock('next/headers', () => ({ headers: async () => new Headers() }));
vi.mock('./providers', () => ({ Providers: providersMock }));

type ProvidersProps = { themeEntries?: string[]; themeModes?: { light: string; dark: string } | null };

function providersProps(): ProvidersProps {
  expect(providersMock).toHaveBeenCalled();
  return providersMock.mock.calls[0][0] as ProvidersProps;
}

function runtimeConfig(doc: Document): Record<string, unknown> {
  const script = doc.querySelector('script:not([src])');
  const body = script?.textContent ?? '';
  return JSON.parse(body.slice(body.indexOf('=') + 1).replace(/;$/, ''));
}

async function renderLayout(): Promise<Document> {
  const html = renderToStaticMarkup(await RootLayout({ children: <span>child</span> }));
  return new DOMParser().parseFromString(html, 'text/html');
}

afterEach(() => {
  providersMock.mockClear();
  vi.restoreAllMocks();
  vi.unstubAllEnvs();
});

describe('RootLayout font', () => {
  it('leaves the body font to --font-family-sans so a theme can set it', async () => {
    const doc = await renderLayout();

    expect(doc.body.className).not.toContain('inter-font-family');
    expect(doc.documentElement.className).toContain('inter-font-variable');
  });

  it('declares the Inter variable that globals.css builds --font-family-sans from', () => {
    const { variable } = interMock.mock.calls[0][0];
    const css = readFileSync(path.join(__dirname, 'globals.css'), 'utf8');
    const rootBlock = css.slice(css.indexOf(':root {'), css.indexOf('}', css.indexOf(':root {')));

    expect(variable).toBe('--font-inter');
    expect(rootBlock).toMatch(/--font-family-sans:\s*var\(--font-inter\),/);
  });
});

describe('RootLayout theme modes', () => {
  it('emits no themeModes and logs nothing when CONSOLE_THEME_MODES is unset', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});

    const doc = await renderLayout();

    expect(runtimeConfig(doc).themeModes).toBeNull();
    expect(providersProps().themeModes ?? null).toBeNull();
    expect(error).not.toHaveBeenCalled();
  });

  it('carries a valid pair to the client and to Providers', async () => {
    vi.stubEnv('CONSOLE_THEMES', 'acme:/themes/acme.css,acme-dark:/themes/dark.css');
    vi.stubEnv('CONSOLE_THEME_MODES', 'light:acme,dark:acme-dark');

    const doc = await renderLayout();

    expect(runtimeConfig(doc).themeModes).toEqual({ light: 'acme', dark: 'acme-dark' });
    expect(providersProps().themeModes).toEqual({ light: 'acme', dark: 'acme-dark' });
  });

  it('ignores a pair naming a theme that is not configured, and logs it once', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.stubEnv('CONSOLE_THEMES', 'acme:/themes/acme.css');
    vi.stubEnv('CONSOLE_THEME_MODES', 'light:acme,dark:ghost');

    const doc = await renderLayout();

    expect(runtimeConfig(doc).themeModes).toBeNull();
    expect(providersProps().themeModes).toBeNull();
    const ignoring = error.mock.calls.filter((call) =>
      String(call[0]).startsWith('[themes] ignoring CONSOLE_THEME_MODES'),
    );
    expect(ignoring).toHaveLength(1);
    expect(String(ignoring[0][0])).toContain('ghost');
  });

  it('ignores a half-specified pair, leaving the built-ins in place', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    vi.stubEnv('CONSOLE_THEMES', 'acme:/themes/acme.css');
    vi.stubEnv('CONSOLE_THEME_MODES', 'light:acme');

    const doc = await renderLayout();

    expect(runtimeConfig(doc).themeModes).toBeNull();
    expect(error).toHaveBeenCalledTimes(1);
  });
});
