import { describe, it, expect } from 'vitest';
import { kvListToRecord, type KV } from './kv';

describe('kvListToRecord', () => {
  it('maps a list of pairs to a record', () => {
    const list: KV[] = [
      { key: 'a', value: '1' },
      { key: 'b', value: '2' },
    ];
    expect(kvListToRecord(list)).toEqual({ a: '1', b: '2' });
  });

  it('trims keys but keeps values untrimmed', () => {
    const list: KV[] = [{ key: '  a  ', value: '  1  ' }];
    expect(kvListToRecord(list)).toEqual({ a: '  1  ' });
  });

  it('skips empty and whitespace-only keys', () => {
    const list: KV[] = [
      { key: '', value: '1' },
      { key: '   ', value: '2' },
      { key: 'a', value: '3' },
    ];
    expect(kvListToRecord(list)).toEqual({ a: '3' });
  });

  it('lets later duplicate keys overwrite earlier ones', () => {
    const list: KV[] = [
      { key: 'a', value: '1' },
      { key: 'a', value: '2' },
    ];
    expect(kvListToRecord(list)).toEqual({ a: '2' });
  });

  it('returns an empty record for an empty list', () => {
    expect(kvListToRecord([])).toEqual({});
  });
});
