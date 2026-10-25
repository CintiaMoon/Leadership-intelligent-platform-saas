import Link from "next/link";

import { roleLabel } from "@/lib/roles";

export type CompanyRow = { id: string; name: string; role: string };

export function RoleBadge({ role }: { role: string }) {
  return (
    <span className="inline-flex items-center rounded-md border border-line px-2 py-0.5 font-sans text-[10px] font-normal leading-4 text-ink">
      {roleLabel(role)}
    </span>
  );
}

/** The user's companies (P5), or the empty state. */
export function CompanyList({ companies }: { companies: CompanyRow[] }) {
  if (companies.length === 0) {
    return (
      <p className="rounded-xl border border-line bg-surface p-5 text-xs leading-[18px] text-muted">
        You do not belong to any company yet. Create one, or open an invitation link you received.
      </p>
    );
  }
  return (
    <ul className="flex flex-col gap-2">
      {companies.map((company) => (
        <li
          key={company.id}
          className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-line bg-surface px-5 py-3"
        >
          <div className="flex min-w-0 items-center gap-3">
            <span className="[overflow-wrap:anywhere] font-serif text-base text-ink">{company.name}</span>
            <RoleBadge role={company.role} />
          </div>
          <Link
            href={`/companies/${company.id}`}
            aria-label={`Open ${company.name}`}
            className="inline-flex h-10 items-center rounded-md border border-line bg-panel px-3 text-base font-medium text-ink hover:bg-surface focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent sm:h-[29px] sm:text-xs"
          >
            Open
          </Link>
        </li>
      ))}
    </ul>
  );
}
