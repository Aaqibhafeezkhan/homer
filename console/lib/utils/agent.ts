import type { AgentInfo, AgentInput, AgentParameterTransformer } from '@/types/homer';

/**
 * Returns the declared inputs for an agent, preferring the canonical
 * `inputs` field and falling back to the deprecated `queryParams` alias.
 *
 * Callers should use this helper instead of reading either field directly
 * so the deprecation can be removed without touching call sites.
 */
export function getAgentInputs(
  agent:
    | Pick<AgentInfo, 'inputs' | 'queryParams'>
    | { inputs?: AgentInput[] | null; queryParams?: AgentInput[] | null }
): AgentInput[] {
  return agent.inputs ?? agent.queryParams ?? [];
}

/**
 * Returns a human-readable description of a transformer
 */
export function getTransformerDescription(
  transformer: AgentParameterTransformer
): string {
  switch (transformer.type) {
    case 'pass':
      return 'Direct pass-through';
    case 'jsonPath':
      return `Extract JSON path: ${transformer.path || 'unknown'}`;
    default:
      return 'Unknown transformer';
  }
}
