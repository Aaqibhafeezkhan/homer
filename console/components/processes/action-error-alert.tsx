/** The banner shown when a process action (Retry, Resume) failed; renders nothing otherwise. */
export function ActionErrorAlert({
  action,
  isError,
  error,
}: {
  action: string;
  isError: boolean;
  error: unknown;
}) {
  if (!isError) return null;
  return (
    <div
      className="rounded-lg border border-destructive bg-destructive/10 p-3 text-sm text-destructive"
      role="alert"
    >
      {`${action} failed: `}{error instanceof Error ? error.message : 'Unknown error'}
    </div>
  );
}
