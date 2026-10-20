'use client';

import { useAgents } from '@/lib/api/queries';
import { Combobox, type ComboboxOption } from '@/components/ui/combobox';

const ALL_AGENTS_LABEL = 'All agents';

interface AgentFilterProps {
  value: string;
  onChange: (value: string) => void;
}

// The empty-string value is the "All agents" entry and clears the filter.
export function AgentFilter({ value, onChange }: AgentFilterProps) {
  const { data: agentsData } = useAgents();

  const options: ComboboxOption[] = [
    { value: '', label: ALL_AGENTS_LABEL },
    ...(agentsData?.agents.map((agent) => ({ value: agent.name, label: agent.name })) ?? []),
  ];

  return (
    <Combobox
      options={options}
      value={value}
      onChange={onChange}
      disabled={!agentsData}
      placeholder={ALL_AGENTS_LABEL}
      searchPlaceholder="Search agents…"
      emptyText="No agents found."
      aria-label="Filter by agent"
      className="w-[220px]"
    />
  );
}
