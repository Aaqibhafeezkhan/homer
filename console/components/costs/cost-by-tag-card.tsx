'use client';

import { useState } from 'react';
import { useCostsByTag } from '@/lib/api/queries';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { CostBreakdownTable } from './cost-breakdown-table';
import type { CostByTagResponse } from '@/types/homer';

// The loaded breakdown for one tag key, or its empty state, plus the truncation note.
function CostByTagResult({ data }: { data: CostByTagResponse }) {
  const values = Object.keys(data.valuesUsd).sort();
  return (
    <>
      {values.length === 0 && (
        <p className="text-sm text-muted-foreground">
          No costs recorded for this tag.
        </p>
      )}
      {values.length > 0 && (
        <CostBreakdownTable
          heading={data.key}
          keys={values}
          valuesUsd={data.valuesUsd}
          totalUsd={data.totalUsd}
        />
      )}
      {data.truncated && (
        <p className="mt-2 text-xs text-muted-foreground">
          Totals cover only the newest 10,000 runs carrying this tag.
        </p>
      )}
    </>
  );
}

export function CostByTagCard({ tagKeys }: { tagKeys: string[] }) {
  const [pickedKey, setPickedKey] = useState<string | undefined>(undefined);
  // Falls back to the first configured key when nothing is picked yet, or the
  // picked one no longer exists (e.g. the config was reloaded) — derived
  // instead of synced via an effect, so there's no extra render.
  const selectedKey = pickedKey && tagKeys.includes(pickedKey) ? pickedKey : tagKeys[0];

  const { data, isLoading, error } = useCostsByTag(selectedKey);

  if (tagKeys.length === 0) {
    return null;
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-center justify-between space-y-0">
        <CardTitle>Cost by tag</CardTitle>
        <select
          aria-label="Select tag key"
          value={selectedKey ?? ''}
          onChange={(e) => setPickedKey(e.target.value)}
          className="flex h-9 rounded-md border border-input bg-background px-3 py-1 text-sm"
        >
          {tagKeys.map((key) => (
            <option key={key} value={key}>
              {key}
            </option>
          ))}
        </select>
      </CardHeader>
      <CardContent>
        {isLoading && (
          <p className="text-sm text-muted-foreground">
            Loading cost by tag...
          </p>
        )}
        {error && (
          <p className="text-sm text-destructive">
            Failed to load cost by tag: {error.message}
          </p>
        )}
        {data && <CostByTagResult data={data} />}
      </CardContent>
    </Card>
  );
}
