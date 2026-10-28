import { isSubscriptionState, toPlanView, type PlanView, type SubscriptionState } from "@/lib/plan";

import { readApi } from "./api";
import type { BffConfig } from "./bff";
import { signInAgain } from "./session";

export type PlanLoad =
  | { kind: "found"; plan: PlanView }
  /** The caller is a member but the plan state refuses the read (a locked company, for everyone but the Owner). */
  | { kind: "restricted"; state: SubscriptionState }
  /** The caller may not read the plan (Agents and Viewers), or the company is not theirs. */
  | { kind: "unavailable" }
  | { kind: "error" };

/**
 * Reads the company's plan for a page, as the caller may see it. Only Owners and Admins can read it; for anyone else
 * the page simply has no plan to show. A failed read is `error`, which the Plan page reports and the banner pages
 * ignore: a missing banner is better than a broken page. An ended session goes to sign in.
 */
export async function readPlan(incoming: Headers, config: BffConfig, companyId: string): Promise<PlanLoad> {
  const result = await readApi<Record<string, unknown>>(incoming, `/api/v1/companies/${companyId}/subscription`, config);
  if (result.ok) {
    return { kind: "found", plan: toPlanView(result.data) };
  }
  if (result.status === 401) {
    signInAgain();
  }
  if (result.status === 403 && result.code === "PLAN_RESTRICTED" && isSubscriptionState(result.subscriptionState)) {
    return { kind: "restricted", state: result.subscriptionState };
  }
  return result.status === 0 || result.status >= 500 ? { kind: "error" } : { kind: "unavailable" };
}

/** The plan for a banner and for disabling controls; null when it cannot be read (the API still decides). */
export async function loadPlan(incoming: Headers, config: BffConfig, companyId: string): Promise<PlanView | null> {
  const result = await readPlan(incoming, config, companyId);
  return result.kind === "found" ? result.plan : null;
}
