'use client';

import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ThemeProvider, useTheme } from 'next-themes';
import { AuthProvider } from '@/lib/auth/auth-context';
import { useEffect, useMemo, useState } from 'react';
import {
  BUILT_IN_THEME_NAMES,
  missingThemeStylesheets,
  resolveThemeEntries,
  type ThemeEntry,
  type ThemeModes,
} from '@/lib/themes/loader';

/**
 * `themeEntries` are the raw `CONSOLE_THEMES` entries and `themeModes` the
 * validated `CONSOLE_THEME_MODES` pair, both resolved server-side by the root
 * layout. They arrive as props rather than being read from `appConfig` here,
 * because `next-themes` renders its pre-paint `<script>` with the theme list
 * inlined: the server never sees `window.__HOMER_CONFIG__`, so a config read
 * here would render four themes on the server and five on the client and fail
 * hydration. The props are the server's answer, so both sides agree — and the
 * pre-paint script knows a custom name in time to apply a stored theme without
 * a flash of the default look.
 *
 * The layout's `x-nonce` rides along as `nonce`: the pre-paint script is an
 * inline `<script>` that production CSP blocks unless it carries the request's
 * nonce, and without it the pair would not be applied until React mounts.
 */
export function Providers({
  children,
  themeEntries,
  themeModes,
  nonce,
}: {
  children: React.ReactNode;
  themeEntries: readonly string[];
  themeModes?: ThemeModes | null;
  nonce?: string;
}) {
  const [queryClient] = useState(createQueryClient);

  const themes = useMemo(() => resolveThemeEntries(themeEntries).entries, [themeEntries]);
  useMissingStylesheetReport(themes);
  const { themeNames, value } = themeProviderLists(themes, themeModes);

  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider
        attribute="class"
        defaultTheme={themeModes ? 'system' : 'dark'}
        enableSystem
        themes={themeNames}
        value={value}
        nonce={nonce}
      >
        {themeModes && <HiddenThemeFallback />}
        <AuthProvider>{children}</AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}

function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        refetchOnWindowFocus: false,
        retry: 1,
      },
    },
  });
}

// A theme file that is absent is a 404: the browser drops the sheet, the
// theme's class applies no tokens and every token falls back to the `:root`
// (light) values, with nothing said. Report it — but only once the load has
// settled (`window` `load`): this effect runs at mount, when a healthy sheet
// may still be in flight and absent from `document.styleSheets`, so probing
// immediately would log a false error on every slow network.
function useMissingStylesheetReport(themes: ThemeEntry[]) {
  useEffect(() => {
    const report = () => {
      const missing = missingThemeStylesheets(
        themes,
        Array.from(document.styleSheets)
          .map((sheet) => sheet.href)
          .filter((href): href is string => href !== null),
      );
      for (const path of missing) {
        console.error(`[themes] stylesheet ${path} did not load; the theme it defines is unused`);
      }
    };
    if (document.readyState === 'complete') {
      report();
      return;
    }
    window.addEventListener('load', report, { once: true });
    return () => window.removeEventListener('load', report);
  }, [themes]);
}

// With a valid pair the built-ins are hidden: `themes` lists only the
// configured names, `System` is the default, and `system` is mapped onto the
// pair so the follower applies the light or dark theme per the OS scheme.
// Without one, every prop is exactly what it was before.
function themeProviderLists(themes: ThemeEntry[], themeModes: ThemeModes | null | undefined) {
  const customNames = themes.map(({ name }) => name);
  const themeNames = themeModes ? customNames : [...BUILT_IN_THEME_NAMES, ...customNames];
  const value = themeModes
    ? Object.fromEntries([
        ...BUILT_IN_THEME_NAMES.map((name) => [name, name]),
        ...customNames.map((name) => [name, name]),
        ['system', 'system'],
        ['light', themeModes.light],
        ['dark', themeModes.dark],
      ])
    : undefined;
  return { themeNames, value };
}

/**
 * Resets a stored built-in (a class the pre-paint script applies before React
 * runs and `next-themes` never validates) to `system` once. Why: ADR-0069,
 * Amendment.
 */
function HiddenThemeFallback() {
  const { theme, setTheme } = useTheme();
  useEffect(() => {
    if (!theme || theme === 'system') return;
    if ((BUILT_IN_THEME_NAMES as readonly string[]).includes(theme)) setTheme('system');
  }, [theme, setTheme]);
  return null;
}
