/**
 * Extract filename from full artifact path
 * @param path - Full path to artifact file
 * @returns Extracted filename
 * @example
 * extractFilename("/tmp/homer/workspace-123/artifacts/cmd_0.out") // "cmd_0.out"
 * extractFilename("cmd_0.out") // "cmd_0.out"
 */
export function extractFilename(path: string): string {
  const parts = path.split('/');
  return parts[parts.length - 1] || 'artifact';
}

/**
 * Convert an artifact path to the form expected by the backend's `path` query
 * parameter (i.e. relative to `artifactsDir`).
 *
 * - Absolute paths (`/tmp/.../artifacts/cmd_0.out`) are stripped to their final
 *   segment — matching the flat-file convention used by `ExecutionInfo.stdOut`.
 * - Already-relative paths (`cmd_0.out`, `subdir/foo.log`) are returned
 *   unchanged so subdirectory artifacts from the picker still resolve.
 */
export function toArtifactApiPath(path: string): string {
  return path.startsWith('/') ? extractFilename(path) : path;
}

/**
 * Trigger browser download from Blob
 * @param blob - Blob data to download
 * @param filename - Name for the downloaded file
 */
export function triggerDownload(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url); // Clean up memory
}
