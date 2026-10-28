import type { Metadata } from "next";

import { CompanyHeader } from "@/components/companies/CompanyHeader";
import { NotAvailable } from "@/components/companies/NotAvailable";
import { PlanDetails } from "@/components/plan/PlanDetails";
import { PlanEnded } from "@/components/plan/PlanEnded";
import { mayManageMembers } from "@/lib/memberActions";
import { waitingForYou } from "@/lib/ownership";
import { loadCompany } from "@/lib/server/company";
import { loadTransfer } from "@/lib/server/ownership";
import { readPlan } from "@/lib/server/subscription";

export const metadata: Metadata = { title: "Plan" };

/**
 * The plan of one company (SPEC-PLAN, plan Q-19). Owners and Admins only; the API refuses everybody else. It shows
 * what the plan is and what it allows, and nothing that changes it: plan changes are made by our team (D-35).
 */
export default async function PlanPage({ params }: { params: Promise<{ companyId: string }> }) {
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
  const read = await readPlan(incoming, config, company.id);
  if (read.kind === "restricted") {
    return <PlanEnded />;
  }
  if (read.kind === "unavailable") {
    return <NotAvailable />;
  }
  if (read.kind === "error") {
    return (
      <>
        <CompanyHeader companyId={company.id} name={company.name} role={company.role} active="plan" />
        <p role="alert" className="mt-5 max-w-[560px] rounded-xl border border-danger/50 bg-surface p-5 text-xs text-danger">
          We could not load the plan. Reload the page to try again.
        </p>
      </>
    );
  }
  const request = company.role === "OWNER" ? null : await loadTransfer(incoming, config, company.id);

  return (
    <>
      <CompanyHeader
        companyId={company.id}
        name={company.name}
        role={company.role}
        active="plan"
        requestWaiting={waitingForYou(request)}
      />
      <PlanDetails plan={read.plan} now={new Date()} />
    </>
  );
}
