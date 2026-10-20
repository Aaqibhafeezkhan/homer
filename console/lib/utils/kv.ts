export interface KV {
  key: string;
  value: string;
}

export function kvListToRecord(list: KV[]): Record<string, string> {
  const out: Record<string, string> = {};
  for (const { key, value } of list) {
    const k = key.trim();
    if (k) out[k] = value;
  }
  return out;
}
