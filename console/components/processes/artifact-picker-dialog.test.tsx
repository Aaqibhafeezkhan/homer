import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ArtifactPickerDialog } from './artifact-picker-dialog';
import { homerApi, type ArtifactListing } from '@/lib/api/client';

const NOW = 1_700_000_000;

function listing(files: ArtifactListing['files']): ArtifactListing {
  return { processId: 7, artifactsDir: '/artifacts/7', files };
}

function renderDialog(open = true) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const onOpenChange = vi.fn();
  const onSelect = vi.fn();
  render(
    <QueryClientProvider client={client}>
      <ArtifactPickerDialog processId={7} open={open} onOpenChange={onOpenChange} onSelect={onSelect} />
    </QueryClientProvider>,
  );
  return { client, onOpenChange, onSelect };
}

describe('ArtifactPickerDialog', () => {
  beforeEach(() => {
    vi.spyOn(Date, 'now').mockReturnValue(NOW * 1000);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('does not list artifacts while closed', () => {
    const list = vi.spyOn(homerApi, 'listArtifacts');
    renderDialog(false);
    expect(list).not.toHaveBeenCalled();
  });

  it('lists the process artifacts under the artifacts query key', async () => {
    const data = listing([{ path: 'out/report.md', size: 2048, modified: NOW - 120 }]);
    const list = vi.spyOn(homerApi, 'listArtifacts').mockResolvedValue(data);
    const { client } = renderDialog();

    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(await screen.findByText('out/report.md')).toBeInTheDocument();
    expect(screen.getByText('2.0 KB')).toBeInTheDocument();
    expect(screen.getByText('2m ago')).toBeInTheDocument();
    expect(list).toHaveBeenCalledWith(7);
    expect(client.getQueryData(['artifacts', 7])).toEqual(data);
  });

  it('selects a file and closes when its row is clicked', async () => {
    vi.spyOn(homerApi, 'listArtifacts').mockResolvedValue(
      listing([{ path: 'a.txt', size: 1, modified: NOW }]),
    );
    const { onSelect, onOpenChange } = renderDialog();

    await userEvent.click(await screen.findByText('a.txt'));
    expect(onSelect).toHaveBeenCalledWith('a.txt');
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });

  it('shows the empty state when the process has no artifacts', async () => {
    vi.spyOn(homerApi, 'listArtifacts').mockResolvedValue(listing([]));
    renderDialog();
    expect(await screen.findByText('No artifacts yet.')).toBeInTheDocument();
  });

  it('shows the empty state when the listing has no files array', async () => {
    vi.spyOn(homerApi, 'listArtifacts').mockResolvedValue({} as ArtifactListing);
    renderDialog();
    expect(await screen.findByText('No artifacts yet.')).toBeInTheDocument();
  });

  it('offers a retry after a failed listing', async () => {
    const list = vi
      .spyOn(homerApi, 'listArtifacts')
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValue(listing([{ path: 'b.txt', size: 1, modified: NOW }]));
    renderDialog();

    expect(await screen.findByText('Failed to load artifacts.')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('b.txt')).toBeInTheDocument();
    expect(list).toHaveBeenCalledTimes(2);
  });

  it('refetches the listing from the refresh button', async () => {
    const list = vi.spyOn(homerApi, 'listArtifacts').mockResolvedValue(listing([]));
    renderDialog();
    await screen.findByText('No artifacts yet.');

    await userEvent.click(screen.getByRole('button', { name: 'Refresh list' }));
    await waitFor(() => expect(list).toHaveBeenCalledTimes(2));
  });

  it('closes from the footer button', async () => {
    vi.spyOn(homerApi, 'listArtifacts').mockResolvedValue(listing([]));
    const { onOpenChange } = renderDialog();
    await screen.findByText('No artifacts yet.');

    // The dialog's corner X shares the name; the footer button is the one without an icon.
    const footerClose = screen.getAllByRole('button', { name: 'Close' }).find((b) => !b.querySelector('svg'));
    await userEvent.click(footerClose!);
    expect(onOpenChange).toHaveBeenCalledWith(false);
  });
});
