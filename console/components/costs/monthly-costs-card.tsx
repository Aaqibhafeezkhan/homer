'use client';

import { useState } from 'react';
import { useMonthlyCosts } from '@/lib/api/queries';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { CostBreakdownTable } from './cost-breakdown-table';
import type { MonthlyCostsResponse } from '@/types/homer';

// The loaded month's per-agent breakdown, or its empty state.
function MonthlyCostsResult({ data }: { data: MonthlyCostsResponse }) {
  const agents = Object.keys(data.perAgentUsd).sort();
  if (agents.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No costs recorded for this month.
      </p>
    );
  }
  return (
    <CostBreakdownTable
      heading="Agent"
      keys={agents}
      valuesUsd={data.perAgentUsd}
      totalUsd={data.totalUsd}
    />
  );
}

export function MonthlyCostsCard() {
  // undefined = let the backend resolve the current UTC month
  const [selectedMonth, setSelectedMonth] = useState<string | undefined>(
    undefined,
  );
  const { data, isLoading, error } = useMonthlyCosts(selectedMonth);

  // The displayed month may have no entries (e.g. a fresh current month),
  // so make sure it is present in the picker alongside availableMonths.
  const months = data
    ? data.availableMonths.includes(data.month)
      ? data.availableMonths
      : [data.month, ...data.availableMonths]
    : [];

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <CardTitle>Monthly costs per agent (UTC)</CardTitle>
        <select
          aria-label="Select month"
          value={data?.month ?? ''}
          onChange={(e) => setSelectedMonth(e.target.value)}
          disabled={!data}
          className="flex h-9 rounded-md border border-input bg-background px-3 py-1 text-sm disabled:cursor-not-allowed disabled:opacity-50"
        >
          {months.map((m) => (
            <option key={m} value={m}>
              {m}
            </option>
          ))}
        </select>
      </CardHeader>
      <CardContent>
        {isLoading && (
          <p className="text-sm text-muted-foreground">
            Loading monthly costs...
          </p>
        )}
        {error && (
          <p className="text-sm text-destructive">
            Failed to load monthly costs: {error.message}
          </p>
        )}
        {data && <MonthlyCostsResult data={data} />}
      </CardContent>
    </Card>
  );
}
