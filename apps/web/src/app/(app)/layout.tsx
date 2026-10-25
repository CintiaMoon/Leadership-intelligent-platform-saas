import type { ReactNode } from "react";

import { AppHeader } from "@/components/app/AppHeader";
import { requireSession } from "@/lib/server/session";

/** The signed-in shell (D-169): a header, then the page. Every page behind it needs a session. */
export default async function AppLayout({ children }: { children: ReactNode }) {
  const { session } = await requireSession();
  return (
    <div className="min-h-dvh bg-page">
      <AppHeader displayName={session.displayName} />
      <main className="mx-auto max-w-[1070px] px-4 py-8 sm:px-8">{children}</main>
    </div>
  );
}
