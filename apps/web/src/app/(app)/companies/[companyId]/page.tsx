import type { Metadata } from "next";

import { LeaveCompany } from "@/components/companies/LeaveCompany";
import { NotAvailable } from "@/components/companies/NotAvailable";
import { RoleBadge } from "@/components/companies/CompanyList";
import { readApi } from "@/lib/server/api";
import { COMPANY_ID, requireSession, signInAgain } from "@/lib/server/session";

export const metadata: Metadata = { title: "Company" };

const article = (role: string) => (/^[AEIOU]/.test(role) ? "an" : "a");

type Company = { id: string; name: string; status: string; role: string };

/**
 * The company page (D-168): there is no company home yet, so every role sees a short page with the company, the role
 * and, for those who can, the way to leave. A company the person cannot use looks the same as one that does not exist.
 */
export default async function CompanyPage({ params }: { params: Promise<{ companyId: string }> }) {
  const { companyId } = await params;
  const { incoming, config } = await requireSession();
  // The id is only ever a selector; anything that is not a UUID never goes to the API.
  if (!COMPANY_ID.test(companyId)) {
    return <NotAvailable />;
  }
  const result = await readApi<Company>(incoming, `/api/v1/companies/${companyId.toLowerCase()}`, config);
  if (!result.ok) {
    if (result.status === 401) {
      signInAgain();
    }
    if (result.status === 0 || result.status >= 500) {
      return (
        <p role="alert" className="rounded-xl border border-danger/50 bg-surface p-5 text-xs text-danger">
          Something went wrong. Reload the page to try again.
        </p>
      );
    }
    return <NotAvailable />;
  }
  const company = result.data;

  return (
    <>
      <div className="border-b border-line pb-4">
        <p className="text-[9px] font-medium uppercase tracking-[0.22em] text-muted">Company</p>
        <h1 className="mt-1 flex flex-wrap items-center gap-3 font-serif text-[32px] leading-9 text-ink">
          <span className="[overflow-wrap:anywhere]">{company.name}</span>
          <RoleBadge role={company.role} />
        </h1>
      </div>
      <section aria-labelledby="home" className="mt-5 max-w-[560px] rounded-xl border border-line bg-surface p-5">
        <h2 id="home" className="font-serif text-base text-ink">
          Company home is coming
        </h2>
        <p className="mt-2 text-xs leading-[18px] text-muted">
          There is nothing to open here yet. You are {article(company.role)} {company.role.toLowerCase()} of this
          company.
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
