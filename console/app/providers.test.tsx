import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { Providers } from './providers';

const themeProviderMock = vi.hoisted(() => vi.fn());
const useThemeMock = vi.hoisted(() => vi.fn());
const setTheme = vi.fn();

vi.mock('next-themes', () => ({
  ThemeProvider: themeProviderMock,
  useTheme: useThemeMock,
}));
vi.mock('@/lib/auth/auth-context', () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => children,
}));

type ThemeProviderProps = {
  themes: string[];
  defaultTheme?: string;
  value?: Record<string, string>;
  nonce?: string;
  children: React.ReactNode;
};

function renderProviders(
  themeEntries: string[] = [],
  themeModes?: { light: string; dark: string },
) {
  return render(
    <Providers themeEntries={themeEntries} themeModes={themeModes}>
      <span>child</span>
    </Providers>,
  );
}

function themeProviderProps(): ThemeProviderProps {
  expect(themeProviderMock).toHaveBeenCalled();
  return themeProviderMock.mock.calls[0][0] as ThemeProviderProps;
}

beforeEach(() => {
  themeProviderMock.mockClear();
  setTheme.mockReset();
  useThemeMock.mockReturnValue({ theme: 'dark', setTheme });
  themeProviderMock.mockImplementation(({ children }: { children: React.ReactNode }) => children);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('Providers', () => {
  it('hands next-themes the built-ins when no theme is configured', () => {
    renderProviders();

    expect(themeProviderProps().themes).toEqual(['light', 'dark', 'terminal', 'win95']);
  });

  it('keeps today’s default theme and value mapping when no pair is set', () => {
    renderProviders(['acme:/themes/acme.css']);

    const props = themeProviderProps();
    expect(props.defaultTheme).toBe('dark');
    expect(props.value).toBeUndefined();
  });

  it('appends the configured theme names to the built-ins', () => {
    renderProviders(['acme:/themes/acme.css', 'acme-dark:/themes/dark.css']);

    expect(themeProviderProps().themes).toEqual([
      'light',
      'dark',
      'terminal',
      'win95',
      'acme',
      'acme-dark',
    ]);
  });

  it('drops an invalid entry rather than handing next-themes a name it cannot use', () => {
    renderProviders(['acme:/themes/acme.css', 'https://evil.example/x.css']);

    expect(themeProviderProps().themes).toEqual(['light', 'dark', 'terminal', 'win95', 'acme']);
  });

  describe('when a light/dark pair is set', () => {
    const pair = { light: 'acme', dark: 'acme-dark' };
    const entries = ['acme:/themes/acme.css', 'acme-dark:/themes/dark.css'];

    it('hands next-themes only the configured names, System-first', () => {
      renderProviders(entries, pair);

      expect(themeProviderProps().themes).toEqual(['acme', 'acme-dark']);
      expect(themeProviderProps().defaultTheme).toBe('system');
    });

    it('maps system onto the pair and every custom name onto itself', () => {
      renderProviders(entries, pair);

      expect(themeProviderProps().value).toEqual({
        light: 'acme',
        dark: 'acme-dark',
        terminal: 'terminal',
        win95: 'win95',
        acme: 'acme',
        'acme-dark': 'acme-dark',
        system: 'system',
      });
    });

    it('keeps the built-ins in the class-removal set so a hidden class is dropped', () => {
      renderProviders(entries, pair);

      // next-themes removes `Object.values(value)` before applying the active
      // one, so a stored hidden built-in's class is removed on the next switch.
      expect(Object.values(themeProviderProps().value ?? {})).toEqual(
        expect.arrayContaining(['terminal', 'win95']),
      );
    });
  });

  it('forwards the CSP nonce so the pre-paint script is allowed in production', () => {
    render(
      <Providers themeEntries={['acme:/themes/acme.css']} nonce="request-nonce">
        <span>child</span>
      </Providers>,
    );

    // next-themes renders its inline pre-paint <script> with `nonce=""` on the
    // server when the prop is absent, which `script-src 'nonce-…'` blocks.
    expect(themeProviderProps().nonce).toBe('request-nonce');
  });

  it('logs a configured theme whose stylesheet never loaded, and still renders', async () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    renderProviders(['acme:/themes/acme.css']);

    expect(await screen.findByText('child')).toBeInTheDocument();
    expect(error.mock.calls.flat().join(' ')).toContain('/themes/acme.css');
  });

  it('says nothing when every configured stylesheet loaded', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    // Stand in for the <link> the root layout emits: jsdom exposes an empty
    // document.styleSheets, so a loaded sheet is simulated by patching href.
    const loaded = { href: 'http://localhost:3000/themes/acme.css' };
    vi.spyOn(document, 'styleSheets', 'get').mockReturnValue({
      0: loaded,
      length: 1,
      item: () => loaded,
      [Symbol.iterator]: function* () {
        yield loaded;
      },
    } as unknown as StyleSheetList);

    renderProviders(['acme:/themes/acme.css']);

    expect(error).not.toHaveBeenCalled();
  });

  describe('when the document is still loading at mount', () => {
    const originalReadyState = Object.getOwnPropertyDescriptor(document, 'readyState');

    // jsdom starts with readyState 'complete', so the deferred-report branch
    // (the one a slow network actually hits) needs the stub to run at all.
    function stubReadyState(readyState: Document['readyState']) {
      Object.defineProperty(document, 'readyState', {
        configurable: true,
        get: () => readyState,
      });
    }

    afterEach(() => {
      if (originalReadyState) {
        Object.defineProperty(document, 'readyState', originalReadyState);
      }
    });

    it('defers the missing-stylesheet report to the window load event', () => {
      const error = vi.spyOn(console, 'error').mockImplementation(() => {});
      stubReadyState('loading');
      renderProviders(['acme:/themes/acme.css']);

      // Not reported yet: the sheet may still be in flight.
      expect(error).not.toHaveBeenCalled();

      fireEvent.load(window);

      expect(error.mock.calls.flat().join(' ')).toContain('/themes/acme.css');
    });

    it('never reports when the component unmounts before load fires', () => {
      const error = vi.spyOn(console, 'error').mockImplementation(() => {});
      stubReadyState('loading');
      const { unmount } = render(
        <Providers themeEntries={['acme:/themes/acme.css']}>
          <span>child</span>
        </Providers>,
      );
      unmount();

      // The listener was removed with the component; a late load reports nothing.
      fireEvent.load(window);

      expect(error).not.toHaveBeenCalled();
    });
  });

  describe('the stored-theme guard', () => {
    const pair = { light: 'acme', dark: 'acme-dark' };
    const entries = ['acme:/themes/acme.css', 'acme-dark:/themes/dark.css'];

    it('falls a stored hidden built-in back to System', async () => {
      useThemeMock.mockReturnValue({ theme: 'terminal', setTheme });
      renderProviders(entries, pair);

      await waitFor(() => expect(setTheme).toHaveBeenCalledWith('system'));
    });

    it('leaves a stored custom theme and System alone', () => {
      useThemeMock.mockReturnValue({ theme: 'acme', setTheme });
      renderProviders(entries, pair);

      expect(setTheme).not.toHaveBeenCalled();
    });

    it('is not mounted when no pair is set', () => {
      useThemeMock.mockReturnValue({ theme: 'terminal', setTheme });
      renderProviders(entries);

      expect(setTheme).not.toHaveBeenCalled();
    });
  });
});
