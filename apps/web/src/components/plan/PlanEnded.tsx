import Link from "next/link";

/**
 * What an Admin, an Agent or a Viewer sees once the plan of their company has ended and it is locked (AC-PLAN-13): only
 * the Owner can open it now. Calm and specific, unlike the page for a company that is not there.
 */
export function PlanEnded() {
  return (
    <div className="rounded-xl border border-line bg-surface p-5">
      <h1 className="font-serif text-[22px] leading-7 text-ink">This company is locked.</h1>
      <p className="mt-2 text-xs leading-[18px] text-muted">
        Its plan has ended, so only its Owner can open it for now. Ask the Owner to contact our team, who can assign a new
        plan. Nothing was deleted.
      </p>
      <Link
        href="/companies"
        className="mt-4 inline-block rounded-sm text-xs font-medium text-ink underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
      >
        Go to my companies
      </Link>
    </div>
  );
}
