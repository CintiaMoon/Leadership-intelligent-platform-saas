import type { Metadata } from "next";

import { CompanyHeader } from "@/components/companies/CompanyHeader";
import { NotAvailable } from "@/components/companies/NotAvailable";
import { InvitationsList } from "@/components/invitations/InvitationsList";
import { InviteButton } from "@/components/invitations/InviteButton";
import { mayManageMembers } from "@/lib/memberActions";
import { toInvitationRows } from "@/lib/rows";
import { readApi } from "@/lib/server/api";
import { loadCompany } from "@/lib/server/company";
import { signInAgain } from "@/lib/server/session";

export const metadata: Metadata = { title: "Pending invitations" };

type InvitationDto = Record<string, unknown>;

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
  const rows = toInvitationRows(invitations.data);

  return (
    <>
      <CompanyHeader companyId={company.id} name={company.name} role={company.role} active="invitations" />
      {company.status !== "ACTIVE" ? (
        <p className="mt-5 rounded-md border border-line bg-surface px-3 py-2 text-xs text-ink">
          This company is scheduled for deletion. Invitations cannot be changed.
        </p>
      ) : null}
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
