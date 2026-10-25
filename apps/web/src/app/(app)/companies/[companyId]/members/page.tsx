import type { Metadata } from "next";

import { CompanyHeader } from "@/components/companies/CompanyHeader";
import { InviteButton } from "@/components/invitations/InviteButton";
import { LeaveCompany } from "@/components/companies/LeaveCompany";
import { MembersList, type MemberRow } from "@/components/companies/MembersList";
import { NotAvailable } from "@/components/companies/NotAvailable";
import { mayManageMembers } from "@/lib/memberActions";
import { readApi } from "@/lib/server/api";
import { loadCompany } from "@/lib/server/company";
import { signInAgain } from "@/lib/server/session";

export const metadata: Metadata = { title: "Members" };

type MemberDto = Omit<MemberRow, "joined"> & { joinedAt: string };

const dateFormat = new Intl.DateTimeFormat("en", { dateStyle: "medium", timeZone: "UTC" });

/** Members of one company (P2, members tab). Owners and Admins only; the API refuses everybody else. */
export default async function MembersPage({ params }: { params: Promise<{ companyId: string }> }) {
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
  const members = await readApi<MemberDto[]>(incoming, `/api/v1/companies/${company.id}/members`, config);
  if (!members.ok) {
    if (members.status === 401) {
      signInAgain();
    }
    if (members.status === 403 || members.status === 404) {
      return <NotAvailable />;
    }
    return (
      <p role="alert" className="rounded-xl border border-danger/50 bg-surface p-5 text-xs text-danger">
        We could not load the members. Reload the page to try again.
      </p>
    );
  }
  const rows: MemberRow[] = members.data.map(({ joinedAt, ...member }) => ({
    ...member,
    joined: dateFormat.format(new Date(joinedAt)),
  }));

  return (
    <>
      <CompanyHeader companyId={company.id} name={company.name} role={company.role} active="members" />
      <div className="mt-5 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-serif text-base text-ink">Members ({rows.length})</h2>
          <div className="mt-2">
            <LeaveCompany companyId={company.id} companyName={company.name} role={company.role} />
          </div>
        </div>
        <InviteButton companyId={company.id} callerRole={company.role} companyStatus={company.status} />
      </div>
      <div className="mt-3">
        <MembersList
          companyId={company.id}
          callerRole={company.role}
          companyStatus={company.status}
          members={rows}
        />
      </div>
    </>
  );
}
