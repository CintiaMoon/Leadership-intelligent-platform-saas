import { formatDate } from "./dates";

/**
 * What the screens know about a company's plan (SPEC-PLAN, plan Q-19). Everything here only decides what to show: the
 * API checks the subscription state on every request and a stale answer ends in `PLAN_RESTRICTED` or
 * `MEMBER_LIMIT_REACHED`, which the screens explain (D-175). The rules mirror `CompanyPolicy`; the tests pin the table.
 */

export type SubscriptionState = "NO_PLAN" | "TRIALING" | "EXPIRED_READ_ONLY" | "EXPIRED_LOCKED" | "ACTIVE";

const STATES: readonly string[] = ["NO_PLAN", "TRIALING", "EXPIRED_READ_ONLY", "EXPIRED_LOCKED", "ACTIVE"];

export function isSubscriptionState(value: unknown): value is SubscriptionState {
  return typeof value === "string" && STATES.includes(value);
}

export type PlanLimitView = { key: string; label: string; value: number; enforced: boolean; usage: number | null };

export type PlanView = {
  state: SubscriptionState;
  /** Null when the company has no plan. */
  plan: { code: string; name: string; version: number } | null;
  /** Already formatted on the server, so the server and the browser print the same text. */
  trialEnds: string | null;
  readOnlyEnds: string | null;
  /** The raw instants, for the days left. */
  trialEndsAt: string | null;
  features: string[];
  limits: PlanLimitView[];
};

const FEATURE_LABELS: Record<string, string> = {
  CORE_SCORING: "Core scoring",
  REPORTS: "Reports",
  HISTORICAL_COMPARISONS: "Historical comparisons",
  CSV_EXPORT: "CSV export",
  ADVANCED_ANALYTICS: "Advanced analytics",
  BENCHMARKING: "Benchmarking",
  ANOMALY_DETECTION: "Anomaly detection",
  EXTENDED_HISTORICAL_REPORTING: "Extended historical reporting",
};

const LIMIT_LABELS: Record<string, string> = {
  TEAMS: "Teams",
  ACTIVE_MEMBERS: "Active members",
  ACTIVE_ASSESSMENTS: "Active assessments",
};

/** Copies the fields by name, as `rows.ts` does, so a field the API adds later never reaches the browser by accident. */
export function toPlanView(dto: Record<string, unknown>): PlanView {
  const plan = dto.plan as Record<string, unknown> | null;
  const trialEndsAt = typeof dto.trialEndsAt === "string" ? dto.trialEndsAt : null;
  const readOnlyEndsAt = typeof dto.readOnlyEndsAt === "string" ? dto.readOnlyEndsAt : null;
  return {
    state: isSubscriptionState(dto.state) ? dto.state : "NO_PLAN",
    plan: plan ? { code: String(plan.code), name: String(plan.name), version: Number(plan.version) } : null,
    trialEnds: trialEndsAt ? formatDate(trialEndsAt) : null,
    readOnlyEnds: readOnlyEndsAt ? formatDate(readOnlyEndsAt) : null,
    trialEndsAt,
    features: ((dto.features as unknown[]) ?? []).map((key) => String(key)),
    limits: ((dto.limits as Record<string, unknown>[]) ?? []).map((limit) => ({
      key: String(limit.key),
      label: LIMIT_LABELS[String(limit.key)] ?? String(limit.key),
      value: Number(limit.value),
      enforced: limit.enforced === true,
      usage: typeof limit.usage === "number" ? limit.usage : null,
    })),
  };
}

export function featureLabel(key: string): string {
  return FEATURE_LABELS[key] ?? key;
}

/** The member limit, if the plan has one the API enforces. */
export function memberLimit(plan: PlanView): { value: number; usage: number } | null {
  const limit = plan.limits.find((l) => l.key === "ACTIVE_MEMBERS" && l.enforced && l.usage !== null);
  return limit ? { value: limit.value, usage: limit.usage ?? 0 } : null;
}

export function atMemberLimit(plan: PlanView | null): boolean {
  const limit = plan ? memberLimit(plan) : null;
  return limit !== null && limit.usage >= limit.value;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/** Whole days left, rounded up, never below zero: the last partial day still counts as a day left. */
export function daysLeft(endsAt: string, now: Date): number {
  return Math.max(0, Math.ceil((new Date(endsAt).getTime() - now.getTime()) / DAY_MS));
}

export const STATE_LABEL: Record<SubscriptionState, string> = {
  NO_PLAN: "No plan",
  TRIALING: "Trial",
  EXPIRED_READ_ONLY: "Trial ended (read-only)",
  EXPIRED_LOCKED: "Plan ended (locked)",
  ACTIVE: "Active",
};

export type PlanAction = "invite" | "resend" | "revoke" | "changeRole" | "reactivate" | "suspend" | "remove";

export type Gate = { allowed: true } | { allowed: false; reason: string };

const OPEN: Gate = { allowed: true };

function running(state: SubscriptionState): boolean {
  return state === "TRIALING" || state === "ACTIVE";
}

/**
 * Whether the subscription state lets the caller use a control, and if not, why (the reason is shown, never silently
 * hidden). `null` means the state is not known to this screen (the person cannot read the plan): the API decides.
 * Mirrors `CompanyPolicy`: inviting, resending, changing roles and reactivating need a running plan; revoking an
 * invitation, suspending and removing need the company not to be locked. A role the matrix never grants is not in
 * question here: the existing `memberActions` and `invitationActions` decide that first.
 */
export function gate(action: PlanAction, state: SubscriptionState | null): Gate {
  if (state === null || running(state)) {
    return OPEN;
  }
  const untilLocked = action === "revoke" || action === "suspend" || action === "remove";
  if (untilLocked && state !== "EXPIRED_LOCKED") {
    return OPEN;
  }
  return { allowed: false, reason: restrictionReason(state) };
}

export function restrictionReason(state: SubscriptionState): string {
  switch (state) {
    case "EXPIRED_READ_ONLY":
      return "Not available: the trial has ended and the company is read-only until our team assigns a plan.";
    case "NO_PLAN":
      return "Not available: this company has no plan yet. Our team assigns one.";
    case "EXPIRED_LOCKED":
      return "Not available: the plan has ended. Our team can assign a new one.";
    default:
      return "Not available.";
  }
}

export const MEMBER_LIMIT_REASON =
  "Not available: the plan's member limit is reached. It works again when fewer members are active.";

/** The reactivation control: the state first, then the member limit. */
export function reactivateGate(state: SubscriptionState | null, atLimit: boolean): Gate {
  const byState = gate("reactivate", state);
  if (!byState.allowed) {
    return byState;
  }
  return atLimit ? { allowed: false, reason: MEMBER_LIMIT_REASON } : OPEN;
}

export type BannerContent = { tone: "info" | "notice"; text: string };

/** The line shown above Members, Pending invitations and Ownership; null when nothing needs saying. */
export function bannerFor(plan: PlanView, now: Date): BannerContent | null {
  switch (plan.state) {
    case "TRIALING": {
      if (!plan.trialEndsAt) {
        return null;
      }
      const days = daysLeft(plan.trialEndsAt, now);
      return {
        tone: "info",
        text: days <= 1 ? "Your trial ends within a day." : `${days} days left in your trial.`,
      };
    }
    case "EXPIRED_READ_ONLY":
      return {
        tone: "notice",
        text: `Your trial has ended${plan.readOnlyEnds ? ` and the company stays read-only until ${plan.readOnlyEnds}` : ""}. You can read everything, but inviting, changing roles and reactivating are off. Our team assigns a plan.`,
      };
    case "EXPIRED_LOCKED":
      return {
        tone: "notice",
        text: "The plan has ended and the company is locked. Only you, the Owner, can open it. Our team can assign a new plan.",
      };
    case "NO_PLAN":
      return {
        tone: "notice",
        text: "This company has no plan yet. You can read everything, but inviting, changing roles and reactivating are off. Our team assigns a plan.",
      };
    default:
      return null;
  }
}
