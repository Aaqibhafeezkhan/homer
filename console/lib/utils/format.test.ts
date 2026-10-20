import { describe, it, expect } from 'vitest';
import { formatDuration, formatBytes } from './format';

describe('formatDuration', () => {
  it('renders seconds-only for sub-minute ranges', () => {
    expect(formatDuration(1000, 1042)).toBe('42s');
  });

  it('renders minutes and seconds for sub-hour ranges', () => {
    expect(formatDuration(1000, 1000 + 125)).toBe('2m 5s');
  });

  it('renders hours, minutes, and seconds for longer ranges', () => {
    const twoHoursThreeMinutesFourSeconds = 2 * 3600 + 3 * 60 + 4;
    expect(formatDuration(0, twoHoursThreeMinutesFourSeconds)).toBe('2h 3m 4s');
  });
});

describe('formatBytes', () => {
  it('renders bytes under 1 KB', () => {
    expect(formatBytes(512)).toBe('512 B');
  });

  it('renders KB with one decimal', () => {
    expect(formatBytes(2048)).toBe('2.0 KB');
  });

  it('renders MB with one decimal', () => {
    expect(formatBytes(5 * 1024 * 1024)).toBe('5.0 MB');
  });

  it('renders GB with two decimals', () => {
    expect(formatBytes(3 * 1024 * 1024 * 1024)).toBe('3.00 GB');
  });
});
