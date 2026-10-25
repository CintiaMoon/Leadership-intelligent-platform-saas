import type { Metadata } from "next";

import { CompanyList, type CompanyRow } from "@/components/companies/CompanyList";
import { CreateCompanyForm } from "@/components/companies/CreateCompanyForm";
import { readApi } from "@/lib/server/api";
import { requireSession, signInAgain } from "@/lib/server/session";

export const metadata: Metadata = { title: "My companies" };

export default async function CompaniesPage() {
  const { incoming, config } = await requireSession();
  const companies = await readApi<CompanyRow[]>(incoming, "/api/v1/companies", config);
  if (!companies.ok && companies.status === 401) {
    signInAgain();
  }

  return (
    <>
      <div className="border-b border-line pb-4">
        <p className="text-[9px] font-medium uppercase tracking-[0.22em] text-muted">Workspace</p>
        <h1 className="mt-1 font-serif text-[32px] leading-9 text-ink">My companies</h1>
        <p className="mt-1.5 text-[11px] text-muted">The companies you belong to, and your role in each.</p>
      </div>

      <div className="mt-5 grid gap-6 lg:grid-cols-[1fr_348px]">
        <section aria-label="Your companies">
          {companies.ok ? (
            <CompanyList companies={companies.data} />
          ) : (
            <p role="alert" className="rounded-xl border border-danger/50 bg-surface p-5 text-xs text-danger">
              We could not load your companies. Reload the page to try again.
            </p>
          )}
        </section>
        <section aria-labelledby="create" className="h-fit rounded-xl border border-line bg-surface p-5">
          <h2 id="create" className="font-serif text-base text-ink">
            Create a company
          </h2>
          <p className="mb-4 mt-1 text-[11px] leading-4 text-muted">You become its Owner.</p>
          <CreateCompanyForm />
        </section>
      </div>
    </>
  );
}
