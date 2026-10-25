import { describe, it, expect, vi } from 'vitest';
import fc from 'fast-check';
import { HomerApiError } from '@/lib/api/client';
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

  it('names the API URL and CORS when no response arrived (network failure or CORS block)', () => {
    expect(describeApiError(new HomerApiError('Failed to fetch'))).toBe(
      "Cannot reach the Homer API at http://localhost:18080 — check that it is running and that its corsAllowedOrigins allows this console's origin.",
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
