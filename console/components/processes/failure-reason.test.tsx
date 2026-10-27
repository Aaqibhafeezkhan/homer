import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { FailureReason } from './failure-reason';

const REASON =
  'Lock skip (lockStrategy.onCollision: SKIP_CURRENT): another run still holds this agent’s lock, so this run never started.';

// The truncated half of the inline badge.
function visibleInlineText(container: HTMLElement): string {
  return container.querySelector('span[aria-hidden="true"]')?.textContent ?? '';
}

describe('FailureReason', () => {
  it('shows the whole reason as plain text in the detail form', () => {
    render(<FailureReason reason={REASON} variant="detail" />);

    expect(screen.getByText('Failed before it started')).toBeInTheDocument();
    expect(screen.getByText(REASON)).toBeInTheDocument();
  });

  it('caps the inline form at 80 characters and keeps the whole reason in the tooltip', () => {
    const long = `${'x'.repeat(200)} end`;
    const { container } = render(<FailureReason reason={long} variant="inline" />);

    expect(visibleInlineText(container)).toHaveLength(80);
    expect(visibleInlineText(container)).toBe(`${'x'.repeat(79)}…`);
    expect(screen.getByTitle(long)).toBeInTheDocument();
    // A screen reader still gets the untruncated text.
    expect(screen.getByText(`Failure reason: ${long}`)).toBeInTheDocument();
  });

  it('renders a reason that already fits the inline form unchanged', () => {
    const short = 'No runner claimed this run within 60 s (tags [linux]).';
    const { container } = render(<FailureReason reason={short} variant="inline" />);

    expect(visibleInlineText(container)).toBe(short);
    expect(visibleInlineText(container)).not.toContain('…');
  });

  it('renders markup in a reason as text, never as HTML', () => {
    render(<FailureReason reason="<b>bold</b> & <script>alert(1)</script>" variant="detail" />);

    expect(screen.getByText('<b>bold</b> & <script>alert(1)</script>')).toBeInTheDocument();
    expect(document.querySelector('b')).toBeNull();
    expect(document.querySelector('script')).toBeNull();
  });

  it('has no axe violations in either form', async () => {
    const detail = render(<FailureReason reason={REASON} variant="detail" />);
    expect(await axe(detail.container)).toHaveNoViolations();

    const inline = render(<FailureReason reason={REASON} variant="inline" />);
    expect(await axe(inline.container)).toHaveNoViolations();
  });
});
