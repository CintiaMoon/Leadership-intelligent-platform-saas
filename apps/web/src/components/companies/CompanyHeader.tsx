import Link from "next/link";

import { mayManageMembers } from "@/lib/memberActions";

import { RoleBadge } from "./CompanyList";

/**
 * The top of every company page (D-169): the company, the person's role, and the tab row (Members and Pending
 * invitations and Plan for Owners and Admins; Ownership for the Owner, and for anyone with an ownership request waiting;
 * Settings is added by its slice). Agents and Viewers have no tabs unless a request is waiting for them.
 */
export function CompanyHeader({
  companyId,
  name,
  role,
  active,
  requestWaiting = false,
}: {
  companyId: string;
  name: string;
  role: string;
  active?: "members" | "invitations" | "ownership" | "plan";
  /** An ownership request is waiting for this person to accept (D-192). */
  requestWaiting?: boolean;
}) {
  const manages = mayManageMembers(role);
  const ownership = role === "OWNER" || requestWaiting;
  const tab = (current: string) =>
    `-mb-px border-b-2 pb-2 text-xs font-medium focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent ${
      active === current ? "border-accent text-ink" : "border-transparent text-muted hover:text-ink"
    }`;
  return (
    <div className="border-b border-line">
      <p className="text-[9px] font-medium uppercase tracking-[0.22em] text-muted">Company</p>
      <h1 className="mt-1 flex flex-wrap items-center gap-3 font-serif text-[32px] leading-9 text-ink">
        <span className="[overflow-wrap:anywhere]">{name}</span>
        <RoleBadge role={role} />
      </h1>
      {manages || ownership ? (
        <nav aria-label="Company" className="mt-4 flex gap-5">
          {manages ? (
            <>
              <Link
                href={`/companies/${companyId}/members`}
                aria-current={active === "members" ? "page" : undefined}
                className={tab("members")}
              >
                Members
              </Link>
              <Link
                href={`/companies/${companyId}/invitations`}
                aria-current={active === "invitations" ? "page" : undefined}
                className={tab("invitations")}
              >
                Pending invitations
              </Link>
              <Link
                href={`/companies/${companyId}/plan`}
                aria-current={active === "plan" ? "page" : undefined}
                className={tab("plan")}
              >
                Plan
              </Link>
            </>
          ) : null}
          {ownership ? (
            <Link
              href={`/companies/${companyId}/ownership`}
              aria-current={active === "ownership" ? "page" : undefined}
              className={tab("ownership")}
            >
              Ownership
            </Link>
          ) : null}
        </nav>
      ) : (
        <div className="pb-4" />
      )}
    </div>
  );
}
