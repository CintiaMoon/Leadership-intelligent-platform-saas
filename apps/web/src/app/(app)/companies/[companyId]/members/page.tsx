import type { Metadata } from "next";

import { CompanyHeader } from "@/components/companies/CompanyHeader";
import { InviteButton } from "@/components/invitations/InviteButton";
import { LeaveCompany } from "@/components/companies/LeaveCompany";
import { MembersList } from "@/components/companies/MembersList";
import { NotAvailable } from "@/components/companies/NotAvailable";
import { PlanBanner } from "@/components/plan/PlanBanner";
import { PlanEnded } from "@/components/plan/PlanEnded";
import { TransferBanner } from "@/components/ownership/TransferBanner";
import { mayManageMembers } from "@/lib/memberActions";
import { waitingForYou } from "@/lib/ownership";
import { toMemberRows } from "@/lib/rows";
import { readApi } from "@/lib/server/api";
import { loadCompany } from "@/lib/server/company";
import { loadTransfer } from "@/lib/server/ownership";
import { loadPlan } from "@/lib/server/subscription";
import { signInAgain } from "@/lib/server/session";

export const metadata: Metadata = { title: "Members" };

type MemberDto = Record<string, unknown>;

/** Members of one company (P2, members tab). Owners and Admins only; the API refuses everybody else. */
export default async function MembersPage({ params }: { params: Promise<{ companyId: string }> }) {
  const { companyId } = await params;
  const loaded = await loadCompany(companyId);
  if (loaded.kind === "restricted") {
    return <PlanEnded />;
  }
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
  const rows = toMemberRows(members.data);
  // The Owner started any request, so only an Admin can be the one it is waiting for.
  const request = company.role === "OWNER" ? null : await loadTransfer(incoming, config, company.id);
  const waiting = waitingForYou(request);
  const plan = await loadPlan(incoming, config, company.id);

  return (
    <>
      <CompanyHeader
        companyId={company.id}
        name={company.name}
        role={company.role}
        active="members"
        requestWaiting={waiting}
      />
      {waiting && request ? <TransferBanner companyId={company.id} initiatorName={request.initiatorName} /> : null}
      <PlanBanner plan={plan} now={new Date()} />
      {company.status !== "ACTIVE" ? (
        <p className="mt-5 rounded-md border border-line bg-surface px-3 py-2 text-xs text-ink">
          This company is scheduled for deletion. Its members cannot be changed.
        </p>
      ) : null}
      <div className="mt-5 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-serif text-base text-ink">Members ({rows.length})</h2>
          <div className="mt-2">
            <LeaveCompany companyId={company.id} companyName={company.name} role={company.role} />
          </div>
        </div>
        <InviteButton
          companyId={company.id}
          callerRole={company.role}
          companyStatus={company.status}
          planState={plan?.state ?? null}
        />
      </div>
      <div className="mt-3">
        <MembersList
          companyId={company.id}
          callerRole={company.role}
          companyStatus={company.status}
          members={rows}
          plan={plan}
        />
      </div>
    </>
  );
}
