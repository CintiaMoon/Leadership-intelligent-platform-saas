import type { Metadata } from "next";
import { redirect } from "next/navigation";

import { CompanyHeader } from "@/components/companies/CompanyHeader";
import { LeaveCompany } from "@/components/companies/LeaveCompany";
import { NotAvailable } from "@/components/companies/NotAvailable";
import { PlanEnded } from "@/components/plan/PlanEnded";
import { TransferBanner } from "@/components/ownership/TransferBanner";
import { mayManageMembers } from "@/lib/memberActions";
import { waitingForYou } from "@/lib/ownership";
import { withArticle } from "@/lib/roles";
import { loadCompany } from "@/lib/server/company";
import { loadTransfer } from "@/lib/server/ownership";

export const metadata: Metadata = { title: "Company" };

/**
 * The company page (D-168): there is no company home yet. Owners and Admins go straight to Members; everybody else sees
 * a short page with their role and the way to leave.
 */
export default async function CompanyPage({ params }: { params: Promise<{ companyId: string }> }) {
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
  if (mayManageMembers(company.role)) {
    redirect(`/companies/${company.id}/members`);
  }

  // Owners and Admins went to Members above, so this is an Agent or a Viewer: an ownership request can be waiting.
  const request = await loadTransfer(incoming, config, company.id);
  const waiting = waitingForYou(request);

  return (
    <>
      <CompanyHeader companyId={company.id} name={company.name} role={company.role} requestWaiting={waiting} />
      {waiting && request ? <TransferBanner companyId={company.id} initiatorName={request.initiatorName} /> : null}
      <section aria-labelledby="home" className="mt-5 max-w-[560px] rounded-xl border border-line bg-surface p-5">
        <h2 id="home" className="font-serif text-base text-ink">
          Company home is coming
        </h2>
        <p className="mt-2 text-xs leading-[18px] text-muted">
          There is nothing to open here yet. You are {withArticle(company.role)} of this company.
        </p>
        {company.status === "PENDING_DELETION" ? (
          <p className="mt-3 rounded-md border border-line px-3 py-2 text-xs text-ink">
            This company is scheduled for deletion.
          </p>
        ) : null}
        <div className="mt-5">
          <LeaveCompany companyId={company.id} companyName={company.name} role={company.role} />
        </div>
      </section>
    </>
  );
}
