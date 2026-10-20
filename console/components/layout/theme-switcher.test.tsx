import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ThemeSwitcher } from './theme-switcher';

const setTheme = vi.fn();
const useThemeMock = vi.hoisted(() => vi.fn());
const appConfigMock = vi.hoisted(() => ({
  themes: { entries: [] as string[], modes: null as { light: string; dark: string } | null },
}));

vi.mock('next-themes', () => ({
  useTheme: useThemeMock,
}));

vi.mock('@/lib/config/app-config', () => ({ appConfig: appConfigMock }));

describe('ThemeSwitcher', () => {
  beforeEach(() => {
    setTheme.mockReset();
    useThemeMock.mockReturnValue({ theme: 'dark', setTheme });
    appConfigMock.themes.entries = [];
    appConfigMock.themes.modes = null;
  });

  it('lists all supported themes in the dropdown', async () => {
    const user = userEvent.setup();
    render(<ThemeSwitcher />);

    await user.click(screen.getByRole('button', { name: /select theme/i }));

    for (const label of ['Light', 'Dark', 'System', 'Terminal', 'Windows 95']) {
      expect(screen.getByRole('menuitemradio', { name: new RegExp(label) })).toBeInTheDocument();
    }
  });

  it('marks the active theme as checked', async () => {
    const user = userEvent.setup();
    useThemeMock.mockReturnValue({ theme: 'terminal', setTheme });
    render(<ThemeSwitcher />);

    await user.click(screen.getByRole('button', { name: /select theme/i }));

    const terminalItem = screen.getByRole('menuitemradio', { name: /terminal/i });
    expect(terminalItem).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('menuitemradio', { name: /^light/i })).toHaveAttribute(
      'aria-checked',
      'false'
    );
  });

  it('calls setTheme when an item is selected', async () => {
    const user = userEvent.setup();
    render(<ThemeSwitcher />);

    await user.click(screen.getByRole('button', { name: /select theme/i }));
    await user.click(screen.getByRole('menuitemradio', { name: /windows 95/i }));

    expect(setTheme).toHaveBeenCalledWith('win95');
  });

  it('offers each configured theme by name, below the built-ins', async () => {
    const user = userEvent.setup();
    appConfigMock.themes.entries = ['acme:/themes/acme.css', 'brand-dark:/themes/brand-dark.css'];
    render(<ThemeSwitcher />);

    await user.click(screen.getByRole('button', { name: /select theme/i }));

    expect(screen.getByRole('menuitemradio', { name: /acme/i })).toBeInTheDocument();
    expect(screen.getByRole('menuitemradio', { name: /brand-dark/i })).toBeInTheDocument();
    for (const label of ['Light', 'Dark', 'System', 'Terminal', 'Windows 95']) {
      expect(screen.getByRole('menuitemradio', { name: new RegExp(label) })).toBeInTheDocument();
    }
  });

  it('selects a configured theme', async () => {
    const user = userEvent.setup();
    appConfigMock.themes.entries = ['acme:/themes/acme.css'];
    render(<ThemeSwitcher />);

    await user.click(screen.getByRole('button', { name: /select theme/i }));
    await user.click(screen.getByRole('menuitemradio', { name: /acme/i }));

    expect(setTheme).toHaveBeenCalledWith('acme');
  });

  it('marks an active configured theme as checked', async () => {
    const user = userEvent.setup();
    appConfigMock.themes.entries = ['acme:/themes/acme.css'];
    useThemeMock.mockReturnValue({ theme: 'acme', setTheme });
    render(<ThemeSwitcher />);

    await user.click(screen.getByRole('button', { name: /select theme/i }));

    expect(screen.getByRole('menuitemradio', { name: /acme/i })).toHaveAttribute(
      'aria-checked',
      'true',
    );
  });

  describe('when a light/dark pair hides the built-ins', () => {
    it('offers only the configured themes plus System', async () => {
      const user = userEvent.setup();
      appConfigMock.themes.entries = ['acme:/themes/acme.css', 'acme-dark:/themes/dark.css'];
      appConfigMock.themes.modes = { light: 'acme', dark: 'acme-dark' };
      render(<ThemeSwitcher />);

      await user.click(screen.getByRole('button', { name: /select theme/i }));

      expect(screen.getAllByRole('menuitemradio')).toHaveLength(3);
      expect(screen.getByRole('menuitemradio', { name: /acme-dark/i })).toBeInTheDocument();
      expect(screen.getByRole('menuitemradio', { name: /system/i })).toBeInTheDocument();
      for (const label of ['Light', 'Dark', 'Terminal', 'Windows 95']) {
        expect(screen.queryByRole('menuitemradio', { name: new RegExp(`^${label}`) })).toBeNull();
      }
    });

    it('selects System, which follows the OS scheme', async () => {
      const user = userEvent.setup();
      appConfigMock.themes.entries = ['acme:/themes/acme.css'];
      appConfigMock.themes.modes = { light: 'acme', dark: 'acme' };
      render(<ThemeSwitcher />);

      await user.click(screen.getByRole('button', { name: /select theme/i }));
      await user.click(screen.getByRole('menuitemradio', { name: /system/i }));

      expect(setTheme).toHaveBeenCalledWith('system');
    });

    it('marks an active configured theme as checked', async () => {
      const user = userEvent.setup();
      appConfigMock.themes.entries = ['acme:/themes/acme.css'];
      appConfigMock.themes.modes = { light: 'acme', dark: 'acme' };
      useThemeMock.mockReturnValue({ theme: 'acme', setTheme });
      render(<ThemeSwitcher />);

      await user.click(screen.getByRole('button', { name: /select theme/i }));

      expect(screen.getByRole('menuitemradio', { name: /acme/i })).toHaveAttribute(
        'aria-checked',
        'true',
      );
    });

    it('keeps the built-ins when an ignored setting left no pair on the config', async () => {
      const user = userEvent.setup();
      // An invalid `CONSOLE_THEME_MODES` is dropped by the root layout, so
      // `modes` never reaches the client and the built-ins stay selectable.
      appConfigMock.themes.entries = ['acme:/themes/acme.css'];
      render(<ThemeSwitcher />);

      await user.click(screen.getByRole('button', { name: /select theme/i }));

      expect(screen.getAllByRole('menuitemradio')).toHaveLength(6);
      expect(screen.getByRole('menuitemradio', { name: /light/i })).toBeInTheDocument();
      expect(screen.getByRole('menuitemradio', { name: /acme/i })).toBeInTheDocument();
    });
  });

  it('adds no item for an invalid or built-in entry', async () => {
    const user = userEvent.setup();
    appConfigMock.themes.entries = ['https://evil.example/x.css', 'dark:/themes/dark.css'];
    render(<ThemeSwitcher />);

    await user.click(screen.getByRole('button', { name: /select theme/i }));

    // The two invalid entries above add no item: the dropdown stays at the
    // four built-ins plus system.
    expect(screen.getAllByRole('menuitemradio')).toHaveLength(4 + 1);
  });
});
