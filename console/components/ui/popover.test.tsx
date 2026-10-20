import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Popover, PopoverTrigger, PopoverContent, PopoverAnchor } from './popover';

describe('Popover', () => {
  it('opens and closes content positioned by PopoverAnchor', async () => {
    const user = userEvent.setup();
    render(
      <Popover>
        <PopoverAnchor asChild>
          <span>anchor</span>
        </PopoverAnchor>
        <PopoverTrigger>Open</PopoverTrigger>
        <PopoverContent>panel body</PopoverContent>
      </Popover>,
    );

    expect(screen.getByText('anchor')).toBeInTheDocument();
    expect(screen.queryByText('panel body')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Open' }));
    expect(screen.getByText('panel body')).toBeInTheDocument();

    await user.keyboard('{Escape}');
    expect(screen.queryByText('panel body')).not.toBeInTheDocument();
  });
});
