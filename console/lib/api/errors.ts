import { HomerApiError } from '@/lib/api/client';
import { activeBaseUrl } from '@/lib/instances/store';

/**
 * One sentence telling the operator why an API query failed, so a page shows
 * the cause instead of waiting forever (#26, #29). A failure with no HTTP status
 * never got a response: the API is down, unreachable or blocked by CORS.
 */
export function describeApiError(error: unknown, notFound = 'Not found.'): string {
  if (!(error instanceof HomerApiError)) {
    return error instanceof Error ? error.message : 'Unknown error';
  }
  if (error.status === undefined) {
    return `Cannot reach the Homer API at ${activeBaseUrl()} — check that it is running and that its corsAllowedOrigins allows this console's origin.`;
  }
  if (error.status === 401 || error.status === 403) {
    return 'The API rejected the request — sign in, or check the console/backend auth configuration.';
  }
  if (error.status === 404) return notFound;
  return `The API request failed with HTTP ${error.status}${error.statusText ? ` ${error.statusText}` : ''}.`;
}
