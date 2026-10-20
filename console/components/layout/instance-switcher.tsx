'use client';

import { useRouter } from 'next/navigation';
import { Check, Plus, Server } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { useInstances } from '@/lib/instances/use-instances';
import { useAuth } from '@/lib/auth/auth-context';

/**
 * Picks the active Homer instance and routes to the login screen to add a new
 * one. Switching resets the query cache + re-probes auth (via
 * `useAuth().switchInstance`); landing on /processes avoids a stale detail
 * route from the previous instance.
 */
export function InstanceSwitcher() {
  const { instances, activeInstanceId } = useInstances();
  const { switchInstance } = useAuth();
  const router = useRouter();
  const active = instances.find((i) => i.id === activeInstanceId);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="sm" className="gap-2" aria-label="Select instance">
          <Server className="h-4 w-4" aria-hidden />
          <span className="max-w-[12rem] truncate">{active?.label ?? 'Instance'}</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="min-w-56">
        <DropdownMenuLabel>Instance</DropdownMenuLabel>
        <DropdownMenuSeparator />
        {instances.map((inst) => (
          <DropdownMenuItem
            key={inst.id}
            role="menuitemradio"
            aria-checked={inst.id === activeInstanceId}
            onSelect={() => {
              if (inst.id !== activeInstanceId) {
                switchInstance(inst.id);
                router.push('/processes');
              }
            }}
          >
            <span className="flex min-w-0 flex-col">
              <span className="truncate">{inst.label}</span>
              <span className="truncate text-xs text-muted-foreground">{inst.baseUrl}</span>
            </span>
            {inst.id === activeInstanceId && <Check className="ml-auto h-4 w-4" aria-hidden />}
          </DropdownMenuItem>
        ))}
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => router.push('/login')}>
          <Plus className="h-4 w-4" aria-hidden />
          <span>Add instance</span>
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
