import type { Metadata } from "next";
import { headers } from "next/headers";
import { Suspense } from "react";

import { AuthShell } from "@/components/auth/AuthShell";
import { AcceptInvitation } from "@/components/invitations/AcceptInvitation";
import { loadConfig, loadSession } from "@/lib/server/bff";

export const metadata: Metadata = { title: "Join a company" };

export default function AcceptInvitationPage() {
  return (
    <AuthShell title="You were invited.">
      <Suspense fallback={<p className="text-xs text-muted">Opening your invitation…</p>}>
        <Accept />
      </Suspense>
    </AuthShell>
  );
}

async function Accept() {
  // The session only decides which buttons to show; the API checks it again on the accept request.
  const session = await loadSession(await headers(), loadConfig());
  return <AcceptInvitation signedIn={session !== null} />;
}
