'use client';

import { useCosts } from '@/lib/api/queries';
import { useCan } from '@/lib/auth/use-can';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { CostUsageBar } from '@/components/costs/cost-usage-bar';
import { MonthlyCostsCard } from '@/components/costs/monthly-costs-card';
import { TotalCostsCard } from '@/components/costs/total-costs-card';
import { CostByTagCard } from '@/components/costs/cost-by-tag-card';
import { formatCost } from '@/lib/utils/format';
import type { CostsResponse } from '@/types/homer';

export default function CostsPage() {
  const { data, isLoading, error } = useCosts();
  const { isAdmin } = useCan();

  if (!isAdmin) {
    return (
      <div className="p-6 text-sm text-muted-foreground">
        You don’t have access to this page.
      </div>
    );
  }

  return (
    <div className="p-6 space-y-6">
      <div>
        <h2 className="text-3xl font-bold tracking-tight">Costs</h2>
        <p className="text-sm text-muted-foreground">
          Rolling-window Claude spend against configured caps, plus persistent
          monthly and all-time statistics.
        </p>
      </div>

      {isLoading && (
        <div className="flex items-center justify-center py-8">
          <div className="text-muted-foreground">Loading costs...</div>
        </div>
      )}

      {error && (
        <div className="rounded-lg border border-destructive bg-destructive/10 p-4">
          <p className="text-sm text-destructive">
            Failed to load costs: {error.message}
          </p>
        </div>
      )}

      {data && <LiveSpendCards data={data} />}

      <MonthlyCostsCard />
      <TotalCostsCard />
      {data && <CostByTagCard tagKeys={data.costTrackedTagKeys} />}
    </div>
  );
}

function LiveSpendCards({ data }: { data: CostsResponse }) {
  return (
    <>
      <Card>
        <CardHeader>
          <CardTitle>Global spend (last 60 s)</CardTitle>
        </CardHeader>
        <CardContent>
          <CostUsageBar
            spent={data.globalUsdLast60s}
            cap={data.globalCapUsdPerMin ?? null}
            capLabel="globalUsdPerMin"
          />
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Per-agent spend (last 24 h)</CardTitle>
        </CardHeader>
        <CardContent>
          <PerAgentTable
            spend={data.perAgentUsdLast24h}
            caps={data.perAgentCapUsdPerDay}
          />
        </CardContent>
      </Card>
    </>
  );
}

function PerAgentTable({
  spend,
  caps,
}: {
  spend: Record<string, number>;
  caps: Record<string, number>;
}) {
  const allAgents = Array.from(
    new Set([...Object.keys(spend), ...Object.keys(caps)]),
  ).sort();

  if (allAgents.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        No agent spend recorded yet.
      </p>
    );
  }

  // Round each addend to the displayed precision first: the Total must equal
  // the sum of the rows as shown, not the sum of the raw values.
  const totalSpent = allAgents.reduce(
    (sum, agent) => sum + Number((spend[agent] ?? 0).toFixed(4)),
    0,
  );

  return (
    <Table>
      <TableHeader>
        <TableRow>
          <TableHead>Agent</TableHead>
          <TableHead className="text-right">Spent (last 24 h)</TableHead>
          <TableHead className="text-right">Cap</TableHead>
          <TableHead className="w-[40%]">Usage</TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        {allAgents.map((agent) => (
          <PerAgentRow key={agent} agent={agent} spent={spend[agent] ?? 0} cap={caps[agent]} />
        ))}
        <TableRow>
          <TableCell className="font-semibold">Total</TableCell>
          <TableCell className="text-right font-mono text-sm font-semibold">
            {formatCost(totalSpent)}
          </TableCell>
          <TableCell className="text-right font-mono text-sm text-muted-foreground">
            —
          </TableCell>
          <TableCell />
        </TableRow>
      </TableBody>
    </Table>
  );
}

function PerAgentRow({ agent, spent, cap }: { agent: string; spent: number; cap: number | undefined }) {
  return (
    <TableRow>
      <TableCell className="font-medium">{agent}</TableCell>
      <TableCell className="text-right font-mono text-sm">
        {formatCost(spent)}
      </TableCell>
      <TableCell className="text-right font-mono text-sm text-muted-foreground">
        {cap != null ? formatCost(cap) : '—'}
      </TableCell>
      <TableCell>
        <CostUsageBar
          spent={spent}
          cap={cap ?? null}
          capLabel={`perAgentUsdPerDay[${agent}]`}
          compact
        />
      </TableCell>
    </TableRow>
  );
}
