import type { ReactNode } from 'react';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { formatCost } from '@/lib/utils/format';

interface CostBreakdownTableProps {
  /** Header of the first column (e.g. "Agent", or the tag key). */
  heading: ReactNode;
  /** Row keys, in display order. */
  keys: string[];
  valuesUsd: Record<string, number>;
  totalUsd: number;
}

/** A two-column "name → spent" table with a Total row, shared by the cost cards. */
export function CostBreakdownTable({ heading, keys, valuesUsd, totalUsd }: CostBreakdownTableProps) {
  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>{heading}</TableHead>
          <TableHead className="text-right">Spent</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {keys.map((key) => (
          <TableRow key={key}>
            <TableCell className="font-medium">{key}</TableCell>
            <TableCell className="text-right font-mono text-sm">
              {formatCost(valuesUsd[key])}
            </TableCell>
          </TableRow>
        ))}
        <TableRow>
          <TableCell className="font-semibold">Total</TableCell>
          <TableCell className="text-right font-mono text-sm font-semibold">
            {formatCost(totalUsd)}
          </TableCell>
        </TableRow>
      </TableBody>
    </Table>
  );
}
