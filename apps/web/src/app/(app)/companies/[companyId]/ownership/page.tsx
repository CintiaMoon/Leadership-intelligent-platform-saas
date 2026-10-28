import type { Metadata } from "next";

import { CompanyHeader } from "@/components/companies/CompanyHeader";
import { NotAvailable } from "@/components/companies/NotAvailable";
import { PlanBanner } from "@/components/plan/PlanBanner";
import { PlanEnded } from "@/components/plan/PlanEnded";
import { OwnershipPanel } from "@/components/ownership/OwnershipPanel";
import { ownershipView, toCandidates, waitingForYou } from "@/lib/ownership";
import { toMemberRows } from "@/lib/rows";
import { readApi } from "@/lib/server/api";
import { loadCompany } from "@/lib/server/company";
import { readTransfer } from "@/lib/server/ownership";
import { loadPlan } from "@/lib/server/subscription";
import { signInAgain } from "@/lib/server/session";

export const metadata: Metadata = { title: "Ownership" };

type MemberDto = Record<string, unknown>;

/**
 * Ownership of one company (SPEC-OWN, D-192). The Owner asks a member to take over, sees the pending request and can
 * cancel it. Anyone else sees the request addressed to them, if there is one, and can accept it. The API refuses
 * everybody else on every request; this page only decides what to show.
 */
export default async function OwnershipPage({ params }: { params: Promise<{ companyId: string }> }) {
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
  const read = await readTransfer(incoming, config, company.id);
  if (read.kind === "error") {
    // Not "nothing pending": showing the form to an Owner whose request exists, or "nothing is waiting" to its
    // recipient, would be wrong on a transient failure.
    return (
      <>
        <CompanyHeader companyId={company.id} name={company.name} role={company.role} active="ownership" />
        <p role="alert" className="mt-5 max-w-[560px] rounded-xl border border-danger/50 bg-surface p-5 text-xs text-danger">
          We could not load the ownership request. Reload the page to try again.
        </p>
      </>
    );
  }
  const transfer = read.kind === "found" ? read.transfer : null;
  const mode = ownershipView(company.role, transfer);

  let candidates: ReturnType<typeof toCandidates> = [];
  if (mode === "owner" && company.status === "ACTIVE" && transfer?.status !== "PENDING") {
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
    candidates = toCandidates(toMemberRows(members.data));
  }

  // Only an Owner or an Admin can read the plan; for anyone else the banner is simply absent.
  const plan = await loadPlan(incoming, config, company.id);

  return (
    <>
      <CompanyHeader
        companyId={company.id}
        name={company.name}
        role={company.role}
        active="ownership"
        requestWaiting={waitingForYou(transfer)}
      />
      <PlanBanner plan={plan} now={new Date()} />
      <OwnershipPanel
        mode={mode}
        companyId={company.id}
        companyName={company.name}
        companyStatus={company.status}
        transfer={transfer}
        candidates={candidates}
        planState={plan?.state ?? null}
      />
    </>
  );
}
