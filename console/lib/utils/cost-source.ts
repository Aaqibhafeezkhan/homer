import type { LlmAccounting, ProcessCostSource } from '@/types/homer';
import { formatCost } from './format';

const COST_SOURCE_LABEL: Record<ProcessCostSource, string> = {
  session: 'proxy',
  cli: 'CLI estimate',
  mixed: 'mixed',
  none: 'not reported',
};

const COST_SOURCE_TITLE: Record<ProcessCostSource, string> = {
  session: 'Priced by the configured session-spend endpoint (llmPrice.sessionSpend).',
  cli: "Estimated by the Claude CLI from its built-in Anthropic rate card. Behind an LLM proxy this is not what you were billed.",
  mixed: 'Charges on this run came from more than one price source.',
  none: 'The CLI reported no cost and no session-spend endpoint answered; nothing was charged.',
};

const GENERIC_COST_SOURCE_TITLE = 'Price source reported by the server.';

export function costSourceLabel(source: string | undefined): string {
  return COST_SOURCE_LABEL[source as ProcessCostSource] ?? source ?? COST_SOURCE_LABEL.cli;
}

/**
 * How a degraded-figure reason reads beside its source. A `cli` reason explains a fallback to
 * the CLI's own figure; a `session` reason (`incomplete: N of M calls ingested`) explains a
 * session figure charged before the endpoint finished ingesting, which is not a fallback.
 */
export function costReasonText(source: string | undefined, reason: string): string {
  return source === 'session' ? reason : `fallback: ${reason}`;
}

/**
 * How a step's cost reads: the resolved figure as charged, or "not reported" when nothing
 * priced it. Without accounting yet (a running step) the CLI's own figure is the fallback.
 */
export function stepCostLabel(accounting: LlmAccounting | null | undefined, cliCostUsd: number | null = null): string {
  if (accounting) return accounting.source === 'none' ? COST_SOURCE_LABEL.none : formatCost(accounting.costUsd);
  return cliCostUsd === null ? COST_SOURCE_LABEL.none : formatCost(cliCostUsd);
}

export function costSourceTitle(source: string | undefined): string {
  if (source === undefined) return COST_SOURCE_TITLE.cli;
  return COST_SOURCE_TITLE[source as ProcessCostSource] ?? GENERIC_COST_SOURCE_TITLE;
}
