'use client';

import { useTotalCosts } from '@/lib/api/queries';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { CostBreakdownTable } from './cost-breakdown-table';

export function TotalCostsCard() {
  const { data, isLoading, error } = useTotalCosts();

  const agents = data ? Object.keys(data.perAgentUsd).sort() : [];

  return (
    <Card>
      <CardHeader>
        <CardTitle>All-time costs per agent</CardTitle>
      </CardHeader>
      <CardContent>
        {isLoading && (
          <p className="text-sm text-muted-foreground">
            Loading total costs...
          </p>
        )}
        {error && (
          <p className="text-sm text-destructive">
            Failed to load total costs: {error.message}
          </p>
        )}
        {data && agents.length === 0 && (
          <p className="text-sm text-muted-foreground">
            No costs recorded yet.
          </p>
        )}
        {data && agents.length > 0 && (
          <CostBreakdownTable
            heading="Agent"
            keys={agents}
            valuesUsd={data.perAgentUsd}
            totalUsd={data.totalUsd}
          />
        )}
      </CardContent>
    </Card>
  );
}
