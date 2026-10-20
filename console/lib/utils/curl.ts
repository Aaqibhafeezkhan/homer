import { agentExecutionPath, type ExecuteAgentOptions } from '@/lib/api/client';
import { activeBaseUrl } from '@/lib/instances/store';

// Wraps a string in single quotes for safe POSIX shell embedding.
// `'\''` ends the quoted run, inserts a literal `'`, and reopens — immune
// to `$`, backticks, and embedded double quotes that bash would otherwise
// expand inside double-quoted strings.
function shellSingleQuote(s: string): string {
  return `'${s.replace(/'/g, `'\\''`)}'`;
}

export function generateAgentCurlCommand(
  agentName: string,
  opts: ExecuteAgentOptions,
): string {
  const url = `${activeBaseUrl()}${agentExecutionPath(agentName, opts.queryParams)}`;

  // The instance URL is user-entered, so it is single-quoted like the body.
  // The auth header stays double-quoted so bash expands $HOMER_AUTH_TOKEN at
  // paste time. The real token must never enter generated text. See ADR-0047 rule 7.
  const lines: string[] = [`curl -X POST ${shellSingleQuote(url)}`];
  lines.push('-H "Authorization: Bearer $HOMER_AUTH_TOKEN"');
  Object.entries(opts.headers ?? {}).forEach(([k, v]) => {
    if (v.trim()) lines.push(`-H '${k}: ${v}'`);
  });

  const hasBody = opts.body && Object.keys(opts.body).length > 0;
  if (hasBody) {
    lines.push(`-H 'Content-Type: application/json'`);
    const json = JSON.stringify(opts.body);
    lines.push(`-d ${shellSingleQuote(json)}`);
  }

  return lines.join(' \\\n  ');
}

/**
 * Copies text to clipboard
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch (error) {
    console.error('Failed to copy to clipboard:', error);
    return false;
  }
}
