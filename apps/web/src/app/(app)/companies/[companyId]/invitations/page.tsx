import type { Metadata } from "next";

import { CompanyHeader } from "@/components/companies/CompanyHeader";
import { NotAvailable } from "@/components/companies/NotAvailable";
import { InvitationsList, type InvitationRow } from "@/components/invitations/InvitationsList";
import { InviteButton } from "@/components/invitations/InviteButton";
import { mayManageMembers } from "@/lib/memberActions";
import { readApi } from "@/lib/server/api";
import { loadCompany } from "@/lib/server/company";
import { signInAgain } from "@/lib/server/session";

export const metadata: Metadata = { title: "Pending invitations" };

type InvitationDto = { id: string; email: string; role: string; expiresAt: string; emailSent: boolean };

const dateFormat = new Intl.DateTimeFormat("en", { dateStyle: "medium", timeZone: "UTC" });

/** Pending invitations of one company (P2, pending tab). Owners and Admins only; the API refuses everybody else. */
export default async function InvitationsPage({ params }: { params: Promise<{ companyId: string }> }) {
  const { companyId } = await params;
  const loaded = await loadCompany(companyId);
  if (loaded.kind === "unavailable") {
    return <NotAvailable />;
  }
  if (loaded.kind === "error") {
    return (
      <p role="alert" className="rounded-xl border border-danger/50 bg-surface p-5 text-xs text-danger">
        Something went wrong. Reload the page to try again.
      </p>
    );
  }
  const { company, incoming, config } = loaded;
  if (!mayManageMembers(company.role)) {
    return <NotAvailable />;
  }
  const invitations = await readApi<InvitationDto[]>(incoming, `/api/v1/companies/${company.id}/invitations`, config);
  if (!invitations.ok) {
    if (invitations.status === 401) {
      signInAgain();
    }
    if (invitations.status === 403 || invitations.status === 404) {
      return <NotAvailable />;
    }
    return (
      <p role="alert" className="rounded-xl border border-danger/50 bg-surface p-5 text-xs text-danger">
        We could not load the invitations. Reload the page to try again.
      </p>
    );
  }
  const rows: InvitationRow[] = invitations.data.map((invitation) => ({
    id: invitation.id,
    email: invitation.email,
    role: invitation.role,
    emailSent: invitation.emailSent,
    expires: dateFormat.format(new Date(invitation.expiresAt)),
  }));

  return (
    <>
      <CompanyHeader companyId={company.id} name={company.name} role={company.role} active="invitations" />
      <div className="mt-5 flex flex-wrap items-start justify-between gap-3">
        <h2 className="font-serif text-base text-ink">Pending invitations ({rows.length})</h2>
        <InviteButton companyId={company.id} callerRole={company.role} companyStatus={company.status} onPendingPage />
      </div>
      <div className="mt-3">
        <InvitationsList
          companyId={company.id}
          callerRole={company.role}
          companyStatus={company.status}
          invitations={rows}
        />
      </div>
    </>
  );
}
