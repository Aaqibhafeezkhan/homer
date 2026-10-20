import { describe, it, expect } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { CostBreakdownTable } from './cost-breakdown-table';

describe('CostBreakdownTable', () => {
  it('renders the heading, one row per key in the given order, and a total row', () => {
    render(
      <CostBreakdownTable
        heading="Agent"
        keys={['alpha', 'beta']}
        valuesUsd={{ beta: 0.5, alpha: 1.25 }}
        totalUsd={1.75}
      />,
    );
    expect(screen.getByRole('columnheader', { name: 'Agent' })).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Spent' })).toBeInTheDocument();

    const rows = screen.getAllByRole('row').slice(1);
    expect(rows.map((r) => within(r).getAllByRole('cell').map((c) => c.textContent))).toEqual([
      ['alpha', '$1.2500'],
      ['beta', '$0.5000'],
      ['Total', '$1.7500'],
    ]);
  });
});
