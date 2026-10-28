import { Badge } from '@/components/ui/badge';
import { ProcessStatus } from '@/types/homer';
import { cn } from '@/lib/utils/cn';
import { Ban, Circle, CircleCheck, CircleHelp, CircleX, Loader2 } from 'lucide-react';

interface StatusBadgeProps {
  status: ProcessStatus;
  /**
   * `badge` (default) renders the wide text badge; `icon` renders one icon
   * carrying the same label as its tooltip and accessible name, for the
   * compact process list.
   */
  variant?: 'badge' | 'icon';
  className?: string;
}

const statusConfig = {
  WORKING: {
    label: 'Working',
    className: 'bg-blue-500 text-white animate-pulse',
    icon: Loader2,
    iconClassName: 'text-blue-500 animate-spin',
  },
  FINISHED: {
    label: 'Finished',
    className: 'bg-green-500 text-white',
    icon: CircleCheck,
    iconClassName: 'text-green-500',
  },
  FAILED: {
    label: 'Failed',
    className: 'bg-red-500 text-white',
    icon: CircleX,
    iconClassName: 'text-red-500',
  },
  KILLED: {
    label: 'Killed',
    className: 'bg-orange-500 text-white',
    icon: Ban,
    iconClassName: 'text-orange-500',
  },
  CREATED: {
    label: 'Created',
    className: 'bg-gray-500 text-white',
    icon: Circle,
    iconClassName: 'text-gray-500',
  },
  UNKNOWN: {
    label: 'Unknown',
    className: 'bg-gray-400 text-white',
    icon: CircleHelp,
    iconClassName: 'text-gray-400',
  },
};

export function StatusBadge({ status, variant = 'badge', className }: StatusBadgeProps) {
  const config = statusConfig[status] || statusConfig.UNKNOWN;
  const label = `Status: ${config.label}`;

  if (variant === 'icon') {
    // The `title` goes on the wrapper: a title *attribute* on an <svg> shows no
    // tooltip in Chrome, only a <title> child does.
    const Icon = config.icon;
    return (
      <span role="img" aria-label={label} title={config.label} className={cn('inline-flex', className)}>
        <Icon className={cn('h-4 w-4', config.iconClassName)} aria-hidden="true" />
      </span>
    );
  }

  return (
    <Badge
      variant="outline"
      aria-label={label}
      className={cn(
        'border-transparent',
        config.className,
        className
      )}
    >
      {config.label}
    </Badge>
  );
}
