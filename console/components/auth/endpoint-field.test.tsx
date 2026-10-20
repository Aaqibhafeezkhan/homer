import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { EndpointField } from './endpoint-field';

type Props = Parameters<typeof EndpointField>[0];

function renderField(overrides: Partial<Props> = {}) {
  const props: Props = {
    visible: true,
    endpoint: 'https://homer.example.com',
    onEndpointChange: vi.fn(),
    onShow: vi.fn(),
    requiresTrustConfirmation: false,
    trustConfirmed: false,
    onTrustConfirmedChange: vi.fn(),
    bearerMode: false,
    mixed: false,
    ...overrides,
  };
  render(<EndpointField {...props} />);
  return props;
}

describe('EndpointField', () => {
  it('folds behind a button naming the host, which reveals the field', async () => {
    const user = userEvent.setup();
    const props = renderField({ visible: false });
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument();
    const change = screen.getByRole('button', { name: 'Change instance endpoint' });
    expect(change).toHaveTextContent('homer.example.com');
    await user.click(change);
    expect(props.onShow).toHaveBeenCalledTimes(1);
  });

  it('summarizes an empty endpoint as the default instance', () => {
    renderField({ visible: false, endpoint: '' });
    expect(screen.getByRole('button', { name: 'Change instance endpoint' })).toHaveTextContent('Default instance');
  });

  it('shows the endpoint in a labelled input and reports edits', () => {
    const props = renderField();
    const input = screen.getByLabelText('Instance endpoint');
    expect(input).toHaveValue('https://homer.example.com');
    fireEvent.change(input, { target: { value: 'https://other.example' } });
    expect(props.onEndpointChange).toHaveBeenCalledWith('https://other.example');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument();
  });

  it.each([
    [false, 'password', 'credentials'],
    [true, 'token', 'token'],
  ])('asks to confirm trust in a link-seeded endpoint (bearer %s)', async (bearerMode, secret, sent) => {
    const user = userEvent.setup();
    const props = renderField({ requiresTrustConfirmation: true, bearerMode, endpoint: 'https://attacker.example' });
    const warning = screen.getByRole('alert');
    expect(warning).toHaveTextContent('came from the page link');
    expect(warning).toHaveTextContent(`Confirm you trust attacker.example before entering your ${secret}`);
    const checkbox = screen.getByRole('checkbox', { name: `I trust this endpoint and want to send my ${sent} to it` });
    expect(checkbox).not.toBeChecked();
    await user.click(checkbox);
    expect(props.onTrustConfirmedChange).toHaveBeenCalledWith(true);
  });

  it('names the raw endpoint when it has no parsable host', () => {
    renderField({ requiresTrustConfirmation: true, endpoint: 'not a url' });
    expect(screen.getByRole('alert').querySelector('strong')).toHaveTextContent('not a url');
  });

  it('reflects a given trust confirmation', () => {
    renderField({ requiresTrustConfirmation: true, trustConfirmed: true });
    expect(screen.getByRole('checkbox')).toBeChecked();
  });

  it('warns about mixed content', () => {
    renderField({ mixed: true, endpoint: 'http://homer.example.com' });
    expect(screen.getByRole('alert')).toHaveTextContent('cannot call an http:// backend');
  });
});
