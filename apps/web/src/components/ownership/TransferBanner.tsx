import Link from "next/link";

/** Shown to the person a pending ownership transfer is waiting for, on the company page and on Members (D-192). */
export function TransferBanner({ companyId, initiatorName }: { companyId: string; initiatorName: string }) {
  return (
    <section
      aria-label="Ownership request"
      className="mt-5 flex flex-wrap items-center justify-between gap-3 rounded-xl border border-accent/60 bg-surface px-5 py-3"
    >
      <p className="min-w-0 flex-1 basis-[240px] text-xs leading-[18px] text-ink [overflow-wrap:anywhere]">
        {initiatorName} asked you to become the Owner of this company.
      </p>
      <Link
        href={`/companies/${companyId}/ownership`}
        className="rounded-sm text-xs font-medium text-ink underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
      >
        Review the request
      </Link>
    </section>
  );
}
