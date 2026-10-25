import { describe, it, expect, vi } from 'vitest';
import fc from 'fast-check';
import { HomerApiError, type TransportFailure } from '@/lib/api/client';
import { describeApiError } from './errors';

vi.mock('@/lib/instances/store', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/instances/store')>()),
  activeBaseUrl: () => 'http://localhost:18080',
}));

describe('describeApiError', () => {
  it.each([401, 403])('tells the operator to sign in or check auth on HTTP %i', (status) => {
    expect(describeApiError(new HomerApiError('x', status, 'Unauthorized'))).toBe(
      'The API rejected the request — sign in, or check the console/backend auth configuration.',
    );
  });

  const transport = (failure?: TransportFailure) => Object.assign(new HomerApiError('x'), { failure });

  it('names the API URL and CORS when the fetch itself failed (network failure or CORS block)', () => {
    expect(describeApiError(transport('unreachable'))).toBe(
      "Cannot reach the Homer API at http://localhost:18080 — check that it is running and that its corsAllowedOrigins allows this console's origin.",
    );
  });

  it('reports a client timeout without blaming CORS', () => {
    expect(describeApiError(transport('timeout'))).toBe(
      'The Homer API at http://localhost:18080 did not answer in time — check that it is running and reachable.',
    );
  });

  it('reports an unreadable response as such, pointing at a proxy rather than CORS', () => {
    expect(describeApiError(transport('invalid-response'))).toBe(
      'The Homer API at http://localhost:18080 sent a response the console cannot read — check for a proxy in between.',
    );
  });

  it('lists the possible causes when a status-less failure is untagged', () => {
    expect(describeApiError(transport())).toBe(
      'The Homer API at http://localhost:18080 did not answer correctly — it may be down, blocked by CORS (check corsAllowedOrigins), or returning an invalid response.',
    );
  });

  it('reports a 404 as not found, in the caller\'s words when given', () => {
    expect(describeApiError(new HomerApiError('x', 404, 'Not Found'))).toBe('Not found.');
    expect(describeApiError(new HomerApiError('x', 404, 'Not Found'), 'Process 9 is gone.')).toBe('Process 9 is gone.');
  });

  it('reports any other status with its code and text', () => {
    expect(describeApiError(new HomerApiError('x', 500, 'Internal Server Error'))).toBe(
      'The API request failed with HTTP 500 Internal Server Error.',
    );
    expect(describeApiError(new HomerApiError('x', 502, ''))).toBe('The API request failed with HTTP 502.');
  });

  it('falls back to the message of an error that did not come from the API client', () => {
    expect(describeApiError(new Error('boom'))).toBe('boom');
    expect(describeApiError('nope')).toBe('Unknown error');
  });

  it('names the status of every failure that is not auth or not-found', () => {
    const status = fc.integer({ min: 300, max: 599 }).filter((s) => ![401, 403, 404].includes(s));
    fc.assert(
      fc.property(status, fc.string(), (s, text) => {
        expect(describeApiError(new HomerApiError('x', s, text))).toContain(`HTTP ${s}`);
      }),
    );
  });
});
