import { bannerFor, type PlanView } from "@/lib/plan";

/** Id of the line that explains why a control is off; the disabled controls point at it with `aria-describedby`. */
export const PLAN_STATUS_ID = "plan-status";

/**
 * The line above Members, Pending invitations and Ownership while the trial runs, and when the company is read-only,
 * has no plan or is locked (plan Q-19). Plain text, not an alert and not a live region: it is on the page when the page
 * loads and nothing here needs an immediate reaction (the live regions of these pages are for the result of an action).
 * Renders nothing for an active plan. `now` is passed in so the page and the tests agree on the day count.
 */
export function PlanBanner({ plan, now }: { plan: PlanView | null; now: Date }) {
  const content = plan ? bannerFor(plan, now) : null;
  if (!content) {
    return null;
  }
  return (
    <p
      id={PLAN_STATUS_ID}
      className={`mt-5 rounded-md border px-3 py-2 text-xs text-ink ${content.tone === "notice" ? "border-accent/60 bg-surface" : "border-line bg-surface"}`}
    >
      {content.text}
    </p>
  );
}
