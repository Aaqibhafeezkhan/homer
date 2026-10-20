import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Combobox, type ComboboxOption } from './combobox';

const options: ComboboxOption[] = [
  { value: '', label: 'All agents' },
  { value: 'apple-bot', label: 'apple-bot' },
  { value: 'banana-bot', label: 'banana-bot' },
];

function renderCombobox(props: Partial<React.ComponentProps<typeof Combobox>> = {}) {
  const onChange = vi.fn();
  render(
    <Combobox
      options={options}
      value=""
      onChange={onChange}
      aria-label="Filter by agent"
      {...props}
    />,
  );
  return { onChange };
}

describe('Combobox', () => {
  it('shows the placeholder and opens the option list on click', async () => {
    const user = userEvent.setup();
    renderCombobox();

    const trigger = screen.getByRole('combobox', { name: 'Filter by agent' });
    expect(trigger).toHaveTextContent('All agents');

    await user.click(trigger);

    expect(screen.getAllByRole('option')).toHaveLength(3);
  });

  it('filters options by case-insensitive substring match', async () => {
    const user = userEvent.setup();
    renderCombobox();

    await user.click(screen.getByRole('combobox', { name: 'Filter by agent' }));
    await user.type(screen.getByPlaceholderText('Search…'), 'APPLE');

    const visible = screen.getAllByRole('option');
    expect(visible).toHaveLength(1);
    expect(visible[0]).toHaveTextContent('apple-bot');
  });

  it('fires onChange with the option value when one is clicked', async () => {
    const user = userEvent.setup();
    const { onChange } = renderCombobox();

    await user.click(screen.getByRole('combobox', { name: 'Filter by agent' }));
    await user.click(screen.getByRole('option', { name: 'banana-bot' }));

    expect(onChange).toHaveBeenCalledWith('banana-bot');
  });

  it('selects the focused option with Enter', async () => {
    const user = userEvent.setup();
    const { onChange } = renderCombobox();

    await user.click(screen.getByRole('combobox', { name: 'Filter by agent' }));
    await user.type(screen.getByPlaceholderText('Search…'), 'banana');
    await user.keyboard('{Enter}');

    expect(onChange).toHaveBeenCalledWith('banana-bot');
  });

  it('closes the panel on Escape without selecting', async () => {
    const user = userEvent.setup();
    const { onChange } = renderCombobox();

    await user.click(screen.getByRole('combobox', { name: 'Filter by agent' }));
    expect(screen.getAllByRole('option').length).toBeGreaterThan(0);

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('option')).not.toBeInTheDocument();
    expect(onChange).not.toHaveBeenCalled();
  });

  it('renders the empty state when nothing matches', async () => {
    const user = userEvent.setup();
    renderCombobox({ emptyText: 'No agents found.' });

    await user.click(screen.getByRole('combobox', { name: 'Filter by agent' }));
    await user.type(screen.getByPlaceholderText('Search…'), 'zzz');

    expect(screen.getByText('No agents found.')).toBeInTheDocument();
    expect(screen.queryByRole('option')).not.toBeInTheDocument();
  });

  it('does not open when disabled', async () => {
    const user = userEvent.setup();
    renderCombobox({ disabled: true });

    const trigger = screen.getByRole('combobox', { name: 'Filter by agent' });
    expect(trigger).toBeDisabled();

    await user.click(trigger);
    expect(screen.queryByRole('option')).not.toBeInTheDocument();
  });
});
