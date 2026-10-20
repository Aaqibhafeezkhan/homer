'use client';

import { cn } from '@/lib/utils/cn';
import { formatCost } from '@/lib/utils/format';

interface CostUsageBarProps {
  spent: number;
  cap: number | null;
  capLabel: string;
  compact?: boolean;
}

// Bar and percentage colors by how close the spend is to the cap.
function usageColors(ratio: number): { bar: string; text: string } {
  if (ratio >= 1) return { bar: 'bg-red-500', text: 'text-red-500' };
  if (ratio >= 0.8) return { bar: 'bg-yellow-500', text: 'text-yellow-500' };
  return { bar: 'bg-green-500', text: 'text-muted-foreground' };
}

export function CostUsageBar({
  spent,
  cap,
  capLabel,
  compact = false,
}: CostUsageBarProps) {
  if (cap == null) {
    return (
      <div className={cn('space-y-1', compact && 'space-y-0.5')}>
        <div className="flex items-baseline justify-between text-sm">
          <span className="font-mono">{formatCost(spent)}</span>
          <span className="text-muted-foreground text-xs">no cap configured</span>
        </div>
      </div>
    );
  }

  const ratio = cap > 0 ? spent / cap : 0;
  const percent = Math.min(100, ratio * 100);
  const overCap = ratio >= 1;
  const colors = usageColors(ratio);

  return (
    <div className={cn('space-y-1', compact && 'space-y-0.5')}>
      {!compact && (
        <div className="flex items-baseline justify-between text-sm">
          <span className="font-mono">
            {formatCost(spent)} / {formatCost(cap)}
          </span>
          <span
            className={cn('text-xs font-medium', colors.text)}
          >
            {percent.toFixed(0)}%
            {overCap && ' — cap exceeded'}
          </span>
        </div>
      )}
      <div
        className="h-2 w-full overflow-hidden rounded-full bg-muted"
        role="progressbar"
        aria-label={capLabel}
        aria-valuenow={percent}
        aria-valuemin={0}
        aria-valuemax={100}
      >
        <div
          className={cn('h-full transition-all', colors.bar)}
          style={{ width: `${percent}%` }}
        />
      </div>
      {compact && (
        <div className="text-xs text-muted-foreground">
          {percent.toFixed(0)}%
        </div>
      )}
    </div>
  );
}
