import { describeApiError } from '@/lib/api/errors';

/** The banner a page shows when the query it renders from failed. */
export function ApiErrorAlert({
  title,
  error,
  notFound,
}: {
  title: string;
  error: unknown;
  notFound?: string;
}) {
  return (
    <div
      className="rounded-lg border border-destructive bg-destructive/10 p-4 text-sm text-destructive"
      role="alert"
    >
      {`${title}: ${describeApiError(error, notFound)}`}
    </div>
  );
}
