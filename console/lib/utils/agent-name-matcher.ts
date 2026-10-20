/** Client mirror of the backend AgentNameMatcher (ADR-0037). UX-only; the server re-checks. */
export function agentNameMatches(pattern: string, name: string): boolean {
  if (pattern === '*') return true;
  if (pattern.endsWith('*')) return name.startsWith(pattern.slice(0, -1));
  return pattern === name;
}
