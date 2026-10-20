import { Badge } from '@/components/ui/badge';
import { ProcessStatus } from '@/types/homer';
import { cn } from '@/lib/utils/cn';

interface StatusBadgeProps {
  status: ProcessStatus;
  className?: string;
}

const statusConfig = {
  WORKING: {
    label: 'Working',
    className: 'bg-blue-500 text-white animate-pulse',
  },
  FINISHED: {
    label: 'Finished',
    className: 'bg-green-500 text-white',
  },
  FAILED: {
    label: 'Failed',
    className: 'bg-red-500 text-white',
  },
  KILLED: {
    label: 'Killed',
    className: 'bg-orange-500 text-white',
  },
  CREATED: {
    label: 'Created',
    className: 'bg-gray-500 text-white',
  },
  UNKNOWN: {
    label: 'Unknown',
    className: 'bg-gray-400 text-white',
  },
};

export function StatusBadge({ status, className }: StatusBadgeProps) {
  const config = statusConfig[status] || statusConfig.UNKNOWN;

  return (
    <Badge
      variant="outline"
      aria-label={`Status: ${config.label}`}
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
