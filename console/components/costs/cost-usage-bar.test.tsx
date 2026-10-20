import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { CostUsageBar } from './cost-usage-bar';

describe('CostUsageBar', () => {
  it('renders spend without progress bar when no cap is configured', () => {
    render(<CostUsageBar spent={1.5} cap={null} capLabel="globalUsdPerMin" />);
    expect(screen.getByText('$1.5000')).toBeInTheDocument();
    expect(screen.getByText(/no cap configured/i)).toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  });

  it('renders progress bar with spent / cap and percent label', () => {
    render(<CostUsageBar spent={2.5} cap={10} capLabel="globalUsdPerMin" />);
    expect(screen.getByText('$2.5000 / $10.0000')).toBeInTheDocument();
    expect(screen.getByText(/25%/)).toBeInTheDocument();

    const bar = screen.getByRole('progressbar', { name: 'globalUsdPerMin' });
    expect(bar).toHaveAttribute('aria-valuenow', '25');
  });

  it('clamps the bar at 100% when spend exceeds the cap', () => {
    render(<CostUsageBar spent={15} cap={10} capLabel="globalUsdPerMin" />);
    expect(screen.getByText(/cap exceeded/i)).toBeInTheDocument();
    const bar = screen.getByRole('progressbar');
    expect(bar).toHaveAttribute('aria-valuenow', '100');
  });

  it('hides the spent / cap label in compact mode but keeps the bar', () => {
    render(
      <CostUsageBar
        spent={2}
        cap={10}
        capLabel="perAgentUsdPerDay[a]"
        compact
      />,
    );
    expect(screen.queryByText('$2.0000 / $10.0000')).not.toBeInTheDocument();
    expect(screen.getByRole('progressbar')).toBeInTheDocument();
    expect(screen.getByText(/20%/)).toBeInTheDocument();
  });

  it('colors the bar and percentage by how close the spend is to the cap', () => {
    const { rerender } = render(<CostUsageBar spent={2} cap={10} capLabel="cap" />);
    expect(screen.getByRole('progressbar').firstElementChild).toHaveClass('bg-green-500');
    expect(screen.getByText('20%')).toHaveClass('text-muted-foreground');

    rerender(<CostUsageBar spent={8} cap={10} capLabel="cap" />);
    expect(screen.getByRole('progressbar').firstElementChild).toHaveClass('bg-yellow-500');
    expect(screen.getByText('80%')).toHaveClass('text-yellow-500');

    rerender(<CostUsageBar spent={10} cap={10} capLabel="cap" />);
    expect(screen.getByRole('progressbar').firstElementChild).toHaveClass('bg-red-500');
    expect(screen.getByText(/100%/)).toHaveClass('text-red-500');
  });
});
