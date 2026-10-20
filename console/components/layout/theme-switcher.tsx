'use client';

import { useTheme } from 'next-themes';
import { Check, Monitor, Moon, Palette, Sun, SquareTerminal } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { appConfig } from '@/lib/config/app-config';
import { resolveThemeEntries } from '@/lib/themes/loader';

type ThemeOption = {
  value: string;
  label: string;
  icon: React.ComponentType<{ className?: string; 'aria-hidden'?: boolean }>;
};

const BUILT_IN_OPTIONS: ThemeOption[] = [
  { value: 'light', label: 'Light', icon: Sun },
  { value: 'dark', label: 'Dark', icon: Moon },
  { value: 'system', label: 'System', icon: Monitor },
  { value: 'terminal', label: 'Terminal', icon: SquareTerminal },
  { value: 'win95', label: 'Windows 95', icon: Palette },
];

const SYSTEM_OPTION: ThemeOption = { value: 'system', label: 'System', icon: Monitor };

/**
 * The built-ins plus one entry per valid `CONSOLE_THEMES` name. Invalid entries
 * are dropped by the parser (and logged once by the root layout), so they add no
 * item here; a custom theme's item is never rendered if it is not loadable.
 *
 * When the root layout resolved a `CONSOLE_THEME_MODES` pair, the built-ins are
 * hidden instead and the list is the configured entries plus `System` — which
 * `next-themes` resolves to that pair's light or dark theme per the OS scheme.
 */
function themeOptions(): ThemeOption[] {
  const custom = resolveThemeEntries(appConfig.themes.entries).entries.map(
    ({ name }): ThemeOption => ({ value: name, label: name, icon: Palette }),
  );
  if (appConfig.themes.modes) return [...custom, SYSTEM_OPTION];
  return [...BUILT_IN_OPTIONS, ...custom];
}

export function ThemeSwitcher() {
  const { theme, setTheme } = useTheme();
  const active = theme ?? 'system';
  const themes = themeOptions();

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" aria-label="Select theme">
          {/* eslint-disable-next-line no-restricted-syntax -- the sun/moon swap keys on the .dark class itself */}
          <Sun className="h-5 w-5 rotate-0 scale-100 transition-all dark:-rotate-90 dark:scale-0" />
          {/* eslint-disable-next-line no-restricted-syntax -- the sun/moon swap keys on the .dark class itself */}
          <Moon className="absolute h-5 w-5 rotate-90 scale-0 transition-all dark:rotate-0 dark:scale-100" />
          <span className="sr-only">Select theme</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuLabel>Theme</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {themes.map(({ value, label, icon: Icon }) => (
          <DropdownMenuItem
            key={value}
            onSelect={() => setTheme(value)}
            aria-checked={active === value}
            role="menuitemradio"
          >
            <Icon className="h-4 w-4" aria-hidden />
            <span className="flex-1">{label}</span>
            {active === value && <Check className="h-4 w-4" aria-hidden />}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
