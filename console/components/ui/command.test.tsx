import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import {
  Command,
  CommandDialog,
  CommandInput,
  CommandList,
  CommandGroup,
  CommandItem,
  CommandSeparator,
  CommandShortcut,
} from './command';

describe('Command', () => {
  it('renders a group heading, item, shortcut and separator', () => {
    render(
      <Command>
        <CommandList>
          <CommandGroup heading="Agents">
            <CommandItem>
              apple-bot
              <CommandShortcut>⌘A</CommandShortcut>
            </CommandItem>
          </CommandGroup>
          <CommandSeparator />
        </CommandList>
      </Command>,
    );

    expect(screen.getByText('Agents')).toBeInTheDocument();
    expect(screen.getByRole('option', { name: /apple-bot/ })).toBeInTheDocument();
    expect(screen.getByText('⌘A')).toBeInTheDocument();
  });

  it('CommandDialog renders its children when open', () => {
    render(
      <CommandDialog open>
        <CommandInput placeholder="Type a command…" />
        <CommandList>
          <CommandItem>run</CommandItem>
        </CommandList>
      </CommandDialog>,
    );

    expect(screen.getByPlaceholderText('Type a command…')).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'run' })).toBeInTheDocument();
  });
});
