'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { cn } from '@/lib/utils/cn';
import { useOpenQuestionsCount, usePendingContinuationsCount } from '@/lib/api/queries';
import { useCan } from '@/lib/auth/use-can';
import { usePlugins } from '@/lib/plugins/plugins-context';
import { canUsePlugin } from '@/lib/plugins/gate';
import { useSidebarCollapsed } from '@/lib/hooks/use-sidebar-collapsed';
import { Button } from '@/components/ui/button';
import {
  Activity,
  CalendarClock,
  DollarSign,
  Layers,
  type LucideIcon,
  MessageCircleQuestion,
  PanelLeftClose,
  PanelLeftOpen,
  Puzzle,
  Server,
  Workflow,
} from 'lucide-react';

interface NavItem {
  name: string;
  href: string;
  icon: LucideIcon;
  adminOnly?: boolean;
}

const navigation: NavItem[] = [
  {
    name: 'Processes',
    href: '/processes',
    icon: Activity,
  },
  {
    name: 'Questions',
    href: '/questions',
    icon: MessageCircleQuestion,
  },
  {
    name: 'Continuations',
    href: '/continuations',
    icon: Workflow,
  },
  {
    name: 'Schedules',
    href: '/schedules',
    icon: CalendarClock,
    adminOnly: true,
  },
  {
    name: 'Agents',
    href: '/agents',
    icon: Layers,
  },
  {
    name: 'Costs',
    href: '/costs',
    icon: DollarSign,
    adminOnly: true,
  },
  {
    name: 'Runners',
    href: '/runners',
    icon: Server,
    adminOnly: true,
  },
];

interface Badge {
  count: number;
  label: string;
}

/**
 * A collapsed link has no room for a visible badge label, so the count is
 * folded into the link's accessible name (the overlay span is aria-hidden).
 */
function linkName(name: string, badge: Badge | undefined): string {
  return badge ? `${name}, ${badge.label}` : name;
}

// A badge for a positive count; none for 0 or a still-loading query.
function countBadge(
  count: number | undefined,
  label: (count: number) => string,
): Badge | undefined {
  return (count ?? 0) > 0 ? { count: count as number, label: label(count as number) } : undefined;
}

// The count overlaid on a collapsed link's icon; its label is in the link's name.
function IconBadge({ badge }: { badge: Badge | undefined }) {
  return badge ? (
    <span
      className="absolute -right-2 -top-2 flex h-4 min-w-4 items-center justify-center rounded-full bg-amber-500 px-1 text-[10px] font-medium leading-none text-white"
      aria-hidden
    >
      {badge.count}
    </span>
  ) : null;
}

// An expanded link's name and its labelled count pill.
function ExpandedLabel({ name, badge }: { name: string; badge: Badge | undefined }) {
  return (
    <>
      {name}
      {badge && (
        <span
          className="ml-auto rounded-full bg-amber-500 px-2 py-0.5 text-xs font-medium text-white"
          aria-label={badge.label}
        >
          {badge.count}
        </span>
      )}
    </>
  );
}

// The nav entries this caller may see: built-ins (admin-gated) then plugins.
function useNavItems(): NavItem[] {
  const { isAdmin, can } = useCan();
  const { plugins } = usePlugins();

  // A denied plugin is already absent from `plugins` — the provider never
  // imported it — but the gate is re-applied here rather than assumed, so the
  // sidebar's answer cannot drift from the route's. See lib/plugins/gate.ts.
  const pluginItems: NavItem[] = plugins
    .filter((p) => canUsePlugin(p.requires, { isAdmin, can }))
    .map((p) => ({ name: p.title, href: p.route, icon: Puzzle }));

  return [
    ...navigation.filter((item) => !item.adminOnly || isAdmin),
    ...pluginItems,
  ];
}

// Badges keyed by href; a count of 0 (or a still-loading query) renders none.
function useNavBadges(): Record<string, Badge | undefined> {
  const { data: openQuestions } = useOpenQuestionsCount();
  const { data: pendingContinuations } = usePendingContinuationsCount();
  return {
    '/questions': countBadge(
      openQuestions,
      (n) => `${n} open question${n === 1 ? '' : 's'} waiting for an answer`,
    ),
    '/continuations': countBadge(
      pendingContinuations,
      (n) => `${n} pending continuation${n === 1 ? '' : 's'} waiting to fire`,
    ),
  };
}

function SidebarLink({
  item,
  badge,
  collapsed,
  isActive,
}: {
  item: NavItem;
  badge: Badge | undefined;
  collapsed: boolean;
  isActive: boolean | undefined;
}) {
  const Icon = item.icon;
  return (
    <Link
      href={item.href}
      title={collapsed ? item.name : undefined}
      aria-label={collapsed ? linkName(item.name, badge) : undefined}
      className={cn(
        'relative flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors',
        collapsed && 'justify-center px-2',
        isActive
          ? 'bg-primary text-primary-foreground'
          : 'hover:bg-accent hover:text-accent-foreground'
      )}
    >
      <span className="relative">
        <Icon className="h-4 w-4" />
        {collapsed && <IconBadge badge={badge} />}
      </span>
      {!collapsed && <ExpandedLabel name={item.name} badge={badge} />}
    </Link>
  );
}

function CollapseToggle({ collapsed, toggle }: { collapsed: boolean; toggle: () => void }) {
  return (
    <div className="border-t p-2">
      <Button
        variant="ghost"
        size={collapsed ? 'icon' : 'sm'}
        className={cn('w-full', !collapsed && 'justify-start')}
        onClick={toggle}
        aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        aria-expanded={!collapsed}
      >
        {collapsed ? (
          <PanelLeftOpen className="h-4 w-4" />
        ) : (
          <>
            <PanelLeftClose className="h-4 w-4" />
            Collapse
          </>
        )}
      </Button>
    </div>
  );
}

export function Sidebar() {
  const pathname = usePathname();
  const { collapsed, toggle } = useSidebarCollapsed();
  const badges = useNavBadges();
  const items = useNavItems();

  return (
    <aside
      className={cn(
        'flex flex-col border-r bg-muted/40 transition-[width]',
        collapsed ? 'w-16' : 'w-64'
      )}
    >
      <nav className={cn('flex flex-1 flex-col gap-1 p-4', collapsed && 'px-2')}>
        {items.map((item) => (
          <SidebarLink
            key={item.name}
            item={item}
            badge={badges[item.href]}
            collapsed={collapsed}
            isActive={pathname?.startsWith(item.href)}
          />
        ))}
      </nav>
      <CollapseToggle collapsed={collapsed} toggle={toggle} />
    </aside>
  );
}
