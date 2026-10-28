import { afterEach, describe, expect, it, vi } from 'vitest';
import fc from 'fast-check';
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

  it('falls back to artifact for an empty path', () => {
    expect(extractFilename('')).toBe('artifact');
  });
});

describe('toArtifactApiPath', () => {
  it('reduces an absolute path to its final segment', () => {
    expect(toArtifactApiPath('/tmp/homer/artifacts/report.txt')).toBe('report.txt');
  });

  it('preserves a relative path including its subdirectory', () => {
    expect(toArtifactApiPath('out/report.txt')).toBe('out/report.txt');
  });

  it('preserves empty and trailing-slash relative paths', () => {
    expect(toArtifactApiPath('')).toBe('');
    expect(toArtifactApiPath('out/')).toBe('out/');
  });
});

describe('download path utilities (property-based)', () => {
  const pathInputs = fc.oneof(
    fc.constantFrom(
      '',
      '/',
      '//',
      '/..',
      '/tmp/..',
      '/tmp/',
      'out/',
      '../report.txt',
      '..',
      '\u0000',
      '\n',
      '\r',
      'out/\u0000',
      '/tmp/\n',
    ),
    fc.string(),
  );

  it('returns a non-empty filename without path separators', async () => {
    await fc.assert(
      fc.property(pathInputs, (path) => {
        const filename = extractFilename(path);
        expect(filename).not.toBe('');
        expect(filename).not.toContain('/');
      }),
    );
  });

  it('strips absolute paths to one filename and preserves relative paths verbatim', async () => {
    await fc.assert(
      fc.property(pathInputs, (path) => {
        const result = toArtifactApiPath(path);
        if (path.startsWith('/')) {
          expect(result).not.toContain('/');
          expect(result).not.toBe('');
        } else {
          expect(result).toBe(path);
        }
      }),
    );
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
