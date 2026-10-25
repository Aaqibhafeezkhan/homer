import { HomerApiError, type TransportFailure } from '@/lib/api/client';
import { activeBaseUrl } from '@/lib/instances/store';

const NO_STATUS: Record<TransportFailure, (url: string) => string> = {
  unreachable: (url) =>
    `Cannot reach the Homer API at ${url} — check that it is running and that its corsAllowedOrigins allows this console's origin.`,
  timeout: (url) => `The Homer API at ${url} did not answer in time — check that it is running and reachable.`,
  'invalid-response': (url) =>
    `The Homer API at ${url} sent a response the console cannot read — check for a proxy in between.`,
};

/** A failure that never got an HTTP status, worded by what the client saw. */
function describeNoStatus(failure: TransportFailure | undefined): string {
  const url = activeBaseUrl();
  if (failure) return NO_STATUS[failure](url);
  return `The Homer API at ${url} did not answer correctly — it may be down, blocked by CORS (check corsAllowedOrigins), or returning an invalid response.`;
}

/**
 * One sentence telling the operator why an API query failed, so a page shows
 * the cause instead of waiting forever (#26, #29).
 */
export function describeApiError(error: unknown, notFound = 'Not found.'): string {
  if (!(error instanceof HomerApiError)) {
    return error instanceof Error ? error.message : 'Unknown error';
  }
  if (error.status === undefined) return describeNoStatus(error.failure);
  if (error.status === 401 || error.status === 403) {
    return 'The API rejected the request — sign in, or check the console/backend auth configuration.';
  }
  if (error.status === 404) return notFound;
  return `The API request failed with HTTP ${error.status}${error.statusText ? ` ${error.statusText}` : ''}.`;
}
