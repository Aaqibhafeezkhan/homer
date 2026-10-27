import { afterEach, describe, expect, it, vi } from 'vitest';
import { extractFilename, toArtifactApiPath, triggerDownload } from './download';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  document.body.innerHTML = '';
});

describe('extractFilename', () => {
  it('extracts the final segment from an absolute path', () => {
    expect(extractFilename('/tmp/homer/artifacts/report.txt')).toBe('report.txt');
  });

  it('returns a bare filename unchanged', () => {
    expect(extractFilename('report.txt')).toBe('report.txt');
  });

  it('falls back to artifact when the path ends with a slash', () => {
    expect(extractFilename('/tmp/homer/artifacts/')).toBe('artifact');
  });
});

describe('toArtifactApiPath', () => {
  it('reduces an absolute path to its final segment', () => {
    expect(toArtifactApiPath('/tmp/homer/artifacts/report.txt')).toBe('report.txt');
  });

  it('preserves a relative path including its subdirectory', () => {
    expect(toArtifactApiPath('out/report.txt')).toBe('out/report.txt');
  });
});

describe('triggerDownload', () => {
  it('clicks and removes the download link, then revokes the object URL', () => {
    const objectUrl = 'blob:download-test';
    const createObjectURL = vi.fn().mockReturnValue(objectUrl);
    const revokeObjectURL = vi.fn();
    vi.stubGlobal('URL', { createObjectURL, revokeObjectURL });

    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const appendChild = vi.spyOn(document.body, 'appendChild');
    const removeChild = vi.spyOn(document.body, 'removeChild');

    triggerDownload(new Blob(['report contents']), 'report.txt');

    const link = appendChild.mock.calls
      .map(([node]) => node)
      .find((node): node is HTMLAnchorElement => node instanceof HTMLAnchorElement);

    expect(createObjectURL).toHaveBeenCalledOnce();
    expect(link).toBeDefined();
    if (!link) {
      throw new Error('Download link was not appended');
    }

    expect(link.href).toBe(objectUrl);
    expect(link.download).toBe('report.txt');
    expect(click).toHaveBeenCalledOnce();
    expect(removeChild).toHaveBeenCalledWith(link);
    expect(document.body.contains(link)).toBe(false);
    expect(revokeObjectURL).toHaveBeenCalledWith(objectUrl);
    expect(click.mock.invocationCallOrder[0]).toBeLessThan(removeChild.mock.invocationCallOrder[0]);
    expect(removeChild.mock.invocationCallOrder[0]).toBeLessThan(revokeObjectURL.mock.invocationCallOrder[0]);
  });
});
