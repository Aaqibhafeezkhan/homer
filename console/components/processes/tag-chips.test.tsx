import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { axe } from 'vitest-axe';
import { TagChips } from './tag-chips';

describe('TagChips', () => {
  it('renders nothing when there are no tags', () => {
    const { container } = render(<TagChips tags={[]} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('links each tag to the filtered process list, url-encoding the value', () => {
    render(<TagChips tags={['mr:15', 'projectId:4711']} />);

    const first = screen.getByRole('link', { name: 'Filter by tag mr:15' });
    expect(first).toHaveAttribute('href', '/processes?tag=mr%3A15');

    // The `:` separator is encoded so the whole tag survives the query round-trip.
    const second = screen.getByRole('link', { name: 'Filter by tag projectId:4711' });
    expect(second).toHaveAttribute('href', '/processes?tag=projectId%3A4711');
  });

  it('invokes onTagClick instead of linking when the callback is provided', () => {
    const onTagClick = vi.fn();
    render(<TagChips tags={['mr:15']} onTagClick={onTagClick} />);

    // No link is rendered in callback mode.
    expect(screen.queryByRole('link')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'Filter by tag mr:15' }));
    expect(onTagClick).toHaveBeenCalledWith('mr:15');
  });

  it('activates the callback on Enter and Space for keyboard users', () => {
    const onTagClick = vi.fn();
    render(<TagChips tags={['mr:15']} onTagClick={onTagClick} />);
    const chip = screen.getByRole('button', { name: 'Filter by tag mr:15' });

    fireEvent.keyDown(chip, { key: 'Enter' });
    fireEvent.keyDown(chip, { key: ' ' });
    expect(onTagClick).toHaveBeenCalledTimes(2);
  });

  it('has no axe violations in either mode', async () => {
    const link = render(<TagChips tags={['mr:15']} />);
    expect(await axe(link.container)).toHaveNoViolations();

    const button = render(<TagChips tags={['mr:15']} onTagClick={() => {}} />);
    expect(await axe(button.container)).toHaveNoViolations();
  });
});
