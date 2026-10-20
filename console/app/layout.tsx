import type { Metadata } from "next";
import { headers } from "next/headers";
import { Inter } from "next/font/google";
import "./globals.css";
import { Providers } from "./providers";
import { serializeForScript } from "@/lib/utils/html";
import { parseAllowedApiOrigins } from "@/lib/security/csp";
import { parsePluginEntries } from "@/lib/plugins/loader";
import { parseThemeEntries, parseThemeModes } from "@/lib/themes/loader";

// Force dynamic rendering so process.env is read at request time, not baked at build time
export const dynamic = 'force-dynamic';

// A variable, not `inter.className`: a font-family class on <body> outranks the
// `--font-family-sans` token, so no theme could set its own font. It sits on
// <html> because `:root` resolves `var(--font-inter)` there.
const inter = Inter({ subsets: ["latin"], variable: "--font-inter" });

export const metadata: Metadata = {
  title: "Homer Console",
  description: "Supervisory console for Homer processes",
};

export default async function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  // Rejected entries are logged server-side, where the operator running the
  // container will see them; the client renders from the surviving list.
  const { entries: themeEntries, rejected: rejectedThemes } = parseThemeEntries(
    process.env.CONSOLE_THEMES,
  );
  for (const reason of rejectedThemes) console.error(`[themes] ignoring ${reason}`);

  // One mapping per render: the `name:path` string is the interchange format
  // between layout, config and switcher (mirroring how `pluginEntries` rides
  // `__HOMER_CONFIG__` — see ADR-0069), so both consumers must agree on it.
  const themeEntryStrings = themeEntries.map(({ name, path }) => `${name}:${path}`);

  // The light/dark pair may only name themes the operator actually configured,
  // so it is resolved against the survivors above. A malformed value is ignored
  // whole — the built-ins stay — and logged once, like a rejected entry.
  const { modes: themeModes, rejected: rejectedThemeModes } = parseThemeModes(
    process.env.CONSOLE_THEME_MODES,
    themeEntries.map(({ name }) => name),
  );
  if (rejectedThemeModes) console.error(`[themes] ignoring CONSOLE_THEME_MODES ${rejectedThemeModes}`);

  const runtimeConfig = {
    apiBaseUrl: process.env.CONSOLE_HOMER_API_URL ?? null,
    authEnabled: process.env.CONSOLE_AUTH_ENABLED === 'true',
    allowBearer: process.env.CONSOLE_ALLOW_BEARER === 'true',
    allowedApiOrigins: parseAllowedApiOrigins(process.env.CONSOLE_ALLOWED_API_ORIGINS),
    pluginEntries: parsePluginEntries(process.env.CONSOLE_PLUGINS),
    themeEntries: themeEntryStrings,
    themeModes,
  };
  // Escape for inline-<script> embedding (defense-in-depth; see serializeForScript).
  const safeJson = serializeForScript(runtimeConfig);
  // Nonce set by middleware so this inline script satisfies the strict CSP.
  const nonce = (await headers()).get('x-nonce') ?? undefined;

  return (
    <html lang="en" className={inter.variable} suppressHydrationWarning>
      <body>
        {/* One same-origin stylesheet per configured theme. React hoists these
            into <head>; a failed load is non-fatal (the theme's class applies no
            tokens, so every token falls back to the `:root` (light) values) and
            `Providers` reports it. CSP already covers them: `style-src 'self'` /
            `font-src 'self'`. See ADR-0069. */}
        {themeEntries.map(({ name, path }) => (
          <link key={name} rel="stylesheet" precedence="theme" href={path} />
        ))}
        <script nonce={nonce} dangerouslySetInnerHTML={{ __html: `window.__HOMER_CONFIG__=${safeJson};` }} />
        <Providers themeEntries={themeEntryStrings} themeModes={themeModes} nonce={nonce}>
          {children}
        </Providers>
      </body>
    </html>
  );
}
