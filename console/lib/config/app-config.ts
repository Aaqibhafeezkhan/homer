import defaultConfig from '@/config/default.json';
import type { ThemeModes } from '@/lib/themes/loader';

export type AuthMode = 'cookie' | 'bearer' | 'disabled';

export interface AppConfig {
  api: {
    baseUrl: string;
    timeout: number;
    /**
     * Operator allowlist of backend origins the console may target
     * (`CONSOLE_ALLOWED_API_ORIGINS`). Empty = unrestricted (any endpoint can be
     * entered at login). When non-empty, the login endpoint field is restricted
     * to these and the CSP `connect-src` is pinned to them. See the 2026-07-12
     * security review (H3 / M2).
     */
    allowedOrigins: string[];
  };
  auth: {
    enabled: boolean;
    /**
     * Selects how the console authenticates requests.
     *  - `cookie`  — POST `/api/v1/auth/login` to mint an `HttpOnly` session
     *               cookie; every request runs with `credentials: 'include'`.
     *               No bearer is sent. Server must be configured with
     *               `apiAuth: !<password>` (or `!<combined>` containing one).
     *  - `bearer`  — legacy localStorage-token flow; `Authorization: Bearer`
     *               on every request. Cookie is not used.
     *  - `disabled` — no auth (matches backend `apiAuth: !<unsecured>`).
     */
    mode: AuthMode;
    /**
     * Whether the login screen offers bearer (token) mode. Defaults to `false`
     * so the console is cookie-only unless the operator opts in via
     * `CONSOLE_ALLOW_BEARER=true`. Bearer tokens live in `localStorage` and are
     * exfiltratable by any XSS foothold; cookie mode is `HttpOnly`. See the
     * 2026-07-12 security review (H3).
     */
    allowBearer: boolean;
    tokenKey: string;
  };
  polling: {
    processListInterval: number;
    processDetailActiveInterval: number;
    processDetailInactiveInterval: number;
    agentsCacheTime: number;
  };
  pagination: {
    defaultLimit: number;
    maxLimit: number;
  };
  ui: {
    defaultTheme: string;
  };
  plugins: {
    /**
     * Raw `CONSOLE_PLUGINS` entries — a same-origin module path, optionally
     * `@<requirement>` (`/plugins/costs.js@admin`). Imported at runtime and
     * executed in the console's own origin, so every entry here is code the
     * operator trusts as much as the console itself. See ADR-0059.
     */
    entries: string[];
  };
  themes: {
    /**
     * Raw `CONSOLE_THEMES` entries — a theme name and the same-origin `.css`
     * path that defines it (`brand-dark:/themes/brand-dark.css`).
     * The stylesheet runs no code, but it *is* served from the console's own
     * origin and can redress the UI, so it carries the same operator trust as
     * anything else on the console's disk. See ADR-0069.
     */
    entries: string[];
    /**
     * The validated `CONSOLE_THEME_MODES` pair (`light:<name>,dark:<name>`).
     * When set, the switcher and `next-themes` offer only the configured
     * entries plus `System`, which resolves to `light`/`dark` per the OS
     * colour scheme; when unset, the built-ins stay. Absent until the root
     * layout resolves a valid pair. See ADR-0069.
     */
    modes?: ThemeModes;
  };
}

interface RuntimeConfig {
  apiBaseUrl: string | null;
  authEnabled: boolean;
  authMode?: AuthMode;
  allowBearer?: boolean;
  allowedApiOrigins?: string[];
  pluginEntries?: string[];
  themeEntries?: string[];
  themeModes?: ThemeModes;
}

function normalizeMode(raw: string | undefined | null): AuthMode | null {
  if (raw === 'cookie' || raw === 'bearer' || raw === 'disabled') return raw;
  return null;
}

// Overlays the injected runtime config on the defaults; an absent key keeps the default.
function applyRuntimeConfig(config: AppConfig, rc: RuntimeConfig): void {
  if (rc.apiBaseUrl) config.api.baseUrl = rc.apiBaseUrl;
  applyRuntimeAuth(config, rc);
  if (rc.allowedApiOrigins) config.api.allowedOrigins = rc.allowedApiOrigins;
  if (rc.pluginEntries) config.plugins.entries = rc.pluginEntries;
  if (rc.themeEntries) config.themes.entries = rc.themeEntries;
  if (rc.themeModes) config.themes.modes = rc.themeModes;
}

function applyRuntimeAuth(config: AppConfig, rc: RuntimeConfig): void {
  config.auth.enabled = rc.authEnabled;
  const mode = normalizeMode(rc.authMode);
  if (mode) config.auth.mode = mode;
  if (rc.allowBearer !== undefined) config.auth.allowBearer = rc.allowBearer;
}

function getConfig(): AppConfig {
  const config: AppConfig = JSON.parse(JSON.stringify(defaultConfig));

  // `default.json` is operator-replaceable at image build time, and `themes` is
  // an additive key a pre-themes copy does not carry; without this the first
  // `getConfig()` throws reading `.entries` off undefined — a blank console.
  config.themes ??= { entries: [] };

  if (typeof window !== 'undefined') {
    // Injected by the root layout on every render. The only client-side source:
    // a browser bundle never sees non-NEXT_PUBLIC_ env vars like CONSOLE_*.
    const rc = (window as Window & { __HOMER_CONFIG__?: RuntimeConfig }).__HOMER_CONFIG__;
    if (rc) applyRuntimeConfig(config, rc);
  }

  return config;
}

export const appConfig = getConfig();
