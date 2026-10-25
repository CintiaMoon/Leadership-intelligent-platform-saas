import Link from "next/link";

/** One calm state for a company the person cannot use, whether it does not exist or is not theirs (D-175). */
export function NotAvailable({ message = "This page is not available." }: { message?: string }) {
  return (
    <div className="rounded-xl border border-line bg-surface p-5">
      <h1 className="font-serif text-[22px] leading-7 text-ink">{message}</h1>
      <p className="mt-2 text-xs leading-[18px] text-muted">It may not exist, or you may not have access to it.</p>
      <Link
        href="/companies"
        className="mt-4 inline-block rounded-sm text-xs font-medium text-ink underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
      >
        Go to my companies
      </Link>
    </div>
  );
}
