import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

// Child components pull in stores / next-themes; stub them — this test is only
// about the logo sizing.
vi.mock('@/components/layout/theme-switcher', () => ({ ThemeSwitcher: () => null }));
vi.mock('@/components/layout/instance-switcher', () => ({ InstanceSwitcher: () => null }));
// Static image import → StaticImageData shape next/image accepts.
vi.mock('../../homer.png', () => ({ default: { src: '/homer.png', height: 512, width: 512 } }));

import { Header } from './header';

describe('Header logo', () => {
  it('constrains the logo height with a utility class, not just the HTML attribute', () => {
    render(<Header />);
    const logo = screen.getByAltText('Homer Console');
    // Regression guard for the "logo renders full-size" bug: Tailwind Preflight's
    // `img { height: auto }` overrides the height attribute, so the size must come
    // from a class (`.h-12`) whose specificity beats the element rule.
    expect(logo).toHaveClass('h-12');
    // Must NOT fall back to only an inline `width: auto` (the broken pattern that
    // left height unconstrained).
    expect(logo.getAttribute('style') ?? '').not.toMatch(/width:\s*auto/);
  });
});
