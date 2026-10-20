'use client';

import Image from 'next/image';
import { ThemeSwitcher } from '@/components/layout/theme-switcher';
import { InstanceSwitcher } from '@/components/layout/instance-switcher';
import homeLogo from '../../homer.png';

export function Header() {
  return (
    <header className="border-b">
      <div className="flex h-16 items-center px-6">
        <div className="flex-1">
          <div className="flex items-center gap-3">
            {/* Size via Tailwind classes, not the height attr / inline width:
                Preflight's `img { height: auto }` (an element rule) overrides the
                HTML height attribute, so the attribute alone let the 512px logo
                render full-size. `.h-12` (class specificity) wins over Preflight. */}
            <Image src={homeLogo} alt="Homer Console" width={48} height={48} className="h-12 w-auto" />
            <span className="text-2xl font-bold">Homer Console</span>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <InstanceSwitcher />
          <ThemeSwitcher />
        </div>
      </div>
    </header>
  );
}
