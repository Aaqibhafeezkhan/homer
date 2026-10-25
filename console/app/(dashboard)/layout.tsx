'use client';

import { useEffect } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { useAuth } from '@/lib/auth/auth-context';
import { loginHref } from '@/lib/auth/return-path';
import { appConfig } from '@/lib/config/app-config';
import { Header } from '@/components/layout/header';
import { MaintenanceBanner } from '@/components/layout/maintenance-banner';
import { Sidebar } from '@/components/layout/sidebar';
import { VersionBadge } from '@/components/layout/version-badge';
import { useAgentReloadStream, useProcessStatusStream } from '@/lib/api/streams';
import { useAuthExpiredHandler } from '@/lib/auth/use-auth-expired-handler';
import { useMarkInAppNavigation } from '@/lib/hooks/use-mark-in-app-navigation';
import { PluginsProvider } from '@/lib/plugins/plugins-context';

export const dynamic = 'force-dynamic';

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const { status } = useAuth();
  const router = useRouter();
  const pathname = usePathname();
  // `status === 'authed'` is the only state in which a session can expire, so it
  // is what separates a real expiry from a session-less first visit.
  const expiredHandled = useAuthExpiredHandler(status === 'authed');

  useEffect(() => {
    // Only redirect on an explicit anon status. While loading, render the
    // skeleton so the cookie-bound auth probe has a chance to land before
    // we bounce the user to /login. Closes P1 #19 (auth-redirect race).
    // Skip if expiry recovery already replaced to /login — a second push here
    // would win the race and drop the expired banner. No `expired` marker
    // here: an anon reader never had a session to expire.
    if (status === 'anon' && appConfig.auth.enabled && !expiredHandled.current) {
      router.push(loginHref(pathname, false));
    }
  }, [status, router, pathname, expiredHandled]);

  useProcessStatusStream();
  useAgentReloadStream();
  useMarkInAppNavigation();

  if (status === 'loading') {
    return (
      <div className="flex min-h-screen items-center justify-center">
        <div className="text-muted-foreground">Loading...</div>
      </div>
    );
  }

  if (status === 'anon' && appConfig.auth.enabled) {
    return null;
  }

  return (
    <PluginsProvider>
      <div className="flex min-h-screen flex-col">
        <MaintenanceBanner />
        <Header />
        <div className="flex flex-1">
          <Sidebar />
          <main id="main" className="flex-1 overflow-auto">
            {children}
          </main>
        </div>
        <VersionBadge />
      </div>
    </PluginsProvider>
  );
}
