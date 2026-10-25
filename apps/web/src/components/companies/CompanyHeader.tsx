import Link from "next/link";

import { mayManageMembers } from "@/lib/memberActions";

import { RoleBadge } from "./CompanyList";

/**
 * The top of every company page (D-169): the company, the person's role, and the tab row. Only Members exists for now;
 * Plan, Settings and Ownership are added by their slices. Agents and Viewers have no tabs.
 */
export function CompanyHeader({
  companyId,
  name,
  role,
  active,
}: {
  companyId: string;
  name: string;
  role: string;
  active?: "members";
}) {
  return (
    <div className="border-b border-line">
      <p className="text-[9px] font-medium uppercase tracking-[0.22em] text-muted">Company</p>
      <h1 className="mt-1 flex flex-wrap items-center gap-3 font-serif text-[32px] leading-9 text-ink">
        <span className="[overflow-wrap:anywhere]">{name}</span>
        <RoleBadge role={role} />
      </h1>
      {mayManageMembers(role) ? (
        <nav aria-label="Company" className="mt-4 flex gap-5">
          <Link
            href={`/companies/${companyId}/members`}
            aria-current={active === "members" ? "page" : undefined}
            className={`-mb-px border-b-2 pb-2 text-xs font-medium focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent ${
              active === "members" ? "border-accent text-ink" : "border-transparent text-muted hover:text-ink"
            }`}
          >
            Members
          </Link>
        </nav>
      ) : (
        <div className="pb-4" />
      )}
    </div>
  );
}
