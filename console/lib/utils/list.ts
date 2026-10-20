/**
 * Splits a comma-separated value into its trimmed, non-empty items, in order;
 * an absent or blank value yields `[]`. Lexing only — it knows no grammar, so
 * each caller still validates the items it gets (the CSP origins, plugin and
 * theme entries, theme modes, tag filters). A setting whose grammar needs more
 * than this (quoting, escaped commas) owns its own split rather than widening
 * this one, so a change for one setting cannot silently change another's.
 */
export function splitCommaList(raw: string | undefined | null): string[] {
  if (!raw) return [];
  return raw
    .split(',')
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}
