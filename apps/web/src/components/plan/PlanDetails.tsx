import { featureLabel, memberLimit, STATE_LABEL, type PlanView } from "@/lib/plan";

import { PlanBanner } from "./PlanBanner";

/**
 * The Plan page for an Owner or an Admin (plan Q-19): the plan and its state, when the trial ends, what the plan
 * includes, and the limits with the member usage. There is no button: plan changes are made by our team (D-35).
 */
export function PlanDetails({ plan, now }: { plan: PlanView; now: Date }) {
  const members = memberLimit(plan);
  const over = members !== null && members.usage > members.value;
  return (
    <>
      <PlanBanner plan={plan} now={now} />
      <section aria-labelledby="plan-heading" className="mt-5 max-w-[560px] rounded-xl border border-line bg-surface p-5">
        <h2 id="plan-heading" className="font-serif text-base text-ink">
          Your plan
        </h2>
        <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-6 gap-y-2 text-xs">
          <dt className="text-muted">Plan</dt>
          <dd className="text-ink">{plan.plan ? `${plan.plan.name} (version ${plan.plan.version})` : "None"}</dd>
          <dt className="text-muted">Status</dt>
          <dd className="text-ink">{STATE_LABEL[plan.state]}</dd>
          {plan.trialEnds ? (
            <>
              <dt className="text-muted">Trial ends</dt>
              <dd className="text-ink">{plan.trialEnds}</dd>
            </>
          ) : null}
          {plan.readOnlyEnds && plan.state !== "ACTIVE" ? (
            <>
              <dt className="text-muted">Read-only until</dt>
              <dd className="text-ink">{plan.readOnlyEnds}</dd>
            </>
          ) : null}
        </dl>
        <p className="mt-4 text-xs leading-[18px] text-muted">Plan changes are made by our team. There is nothing to change here.</p>
      </section>

      {plan.limits.length > 0 ? (
        <section aria-labelledby="limits-heading" className="mt-4 max-w-[560px] rounded-xl border border-line bg-surface p-5">
          <h2 id="limits-heading" className="font-serif text-base text-ink">
            Limits
          </h2>
          <ul className="mt-3 flex flex-col gap-2 text-xs">
            {plan.limits.map((limit) => (
              <li key={limit.key} className="flex flex-wrap justify-between gap-x-6 text-ink">
                <span>{limit.label}</span>
                <span>
                  {limit.enforced && limit.usage !== null ? `${limit.usage} of ${limit.value} used` : `Up to ${limit.value}`}
                </span>
              </li>
            ))}
          </ul>
          {over ? (
            <p className="mt-3 rounded-md border border-line px-3 py-2 text-xs text-ink">
              You have more active members than this plan allows. Nobody was removed or suspended. Reactivating a member
              or accepting an invitation works again when fewer members are active.
            </p>
          ) : null}
        </section>
      ) : null}

      {plan.features.length > 0 ? (
        <section aria-labelledby="features-heading" className="mt-4 max-w-[560px] rounded-xl border border-line bg-surface p-5">
          <h2 id="features-heading" className="font-serif text-base text-ink">
            Included
          </h2>
          <ul className="mt-3 flex flex-col gap-1 text-xs text-ink">
            {plan.features.map((feature) => (
              <li key={feature}>{featureLabel(feature)}</li>
            ))}
          </ul>
        </section>
      ) : null}
    </>
  );
}
