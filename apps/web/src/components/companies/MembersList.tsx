"use client";

import { useState } from "react";

import { ConfirmDialog } from "@/components/app/ConfirmDialog";
import { PasswordDialog } from "@/components/app/PasswordDialog";
import { useActionRunner } from "@/components/app/useActionRunner";
import { DANGER_BUTTON, SECONDARY_BUTTON } from "@/components/app/buttons";
import { send } from "@/lib/api/client";
import { memberActions } from "@/lib/memberActions";
import { roleLabel } from "@/lib/roles";

import { RoleBadge } from "./CompanyList";

export type MemberRow = {
  id: string;
  displayName: string;
  email: string;
  role: string;
  status: string;
  /** Already formatted on the server, so the server and the browser print the same text. */
  joined: string;
  self: boolean;
};

type Confirm = { kind: "suspend" | "remove"; member: MemberRow };

const STATUS_LABEL: Record<string, string> = { ACTIVE: "Active", SUSPENDED: "Suspended" };

/**
 * The members of one company with the actions the caller may take (P2). What is shown comes from `memberActions`; the
 * API decides every request. After any answer the list is read again, so a stale row corrects itself.
 */
export function MembersList({
  companyId,
  callerRole,
  companyStatus,
  members,
}: {
  companyId: string;
  callerRole: string;
  companyStatus: string;
  members: MemberRow[];
}) {
  const { notice, busy, run, reauthOpen, reauthenticated, cancelReauth } = useActionRunner("member");
  const [confirm, setConfirm] = useState<Confirm | null>(null);
  const [confirmPending, setConfirmPending] = useState(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);

  const base = `/api/companies/${companyId}/members`;

  function changeRole(member: MemberRow, role: string) {
    void run(member.id, () => send("PATCH", `${base}/${member.id}`, { role }), `${member.displayName} is now ${article(role)} ${role.toLowerCase()}.`);
  }

  function reactivate(member: MemberRow) {
    void run(member.id, () => send("POST", `${base}/${member.id}/reactivate`), `${member.displayName} was reactivated.`);
  }

  async function confirmed() {
    if (!confirm || confirmPending) {
      return;
    }
    const { kind, member } = confirm;
    setConfirmPending(true);
    setConfirmError(null);
    const outcome =
      kind === "suspend"
        ? await run(member.id, () => send("POST", `${base}/${member.id}/suspend`), `${member.displayName} was suspended.`)
        : await run(member.id, () => send("DELETE", `${base}/${member.id}`), `${member.displayName} was removed.`);
    setConfirmPending(false);
    if (outcome.done) {
      setConfirm(null);
    } else {
      setConfirmError(outcome.error);
    }
  }

  return (
    <>
      <div role="status" aria-live="polite" className={notice ? "mb-4" : ""}>
        {notice ? <p className="rounded-md border border-line bg-surface px-3 py-2 text-xs text-ink">{notice}</p> : null}
      </div>
      <ul className="flex flex-col gap-2" aria-label="Members">
        {members.map((member) => {
          const actions = memberActions(callerRole, companyStatus, member);
          const rowBusy = busy === member.id;
          const hasActions = actions.roles.length > 0 || actions.suspend || actions.reactivate || actions.remove;
          return (
            <li
              key={member.id}
              className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 rounded-xl border border-line bg-surface px-5 py-3"
            >
              <div className="min-w-0 flex-1 basis-[220px]">
                <p className="[overflow-wrap:anywhere] text-xs font-semibold text-ink">
                  {member.displayName}
                  {member.self ? <span className="ml-1.5 font-normal text-muted">(you)</span> : null}
                </p>
                <p className="[overflow-wrap:anywhere] text-[11px] text-muted">{member.email}</p>
                <p className="mt-1 text-[11px] text-muted">Joined {member.joined}</p>
              </div>
              <div className="flex items-center gap-2">
                {actions.roles.length === 0 ? <RoleBadge role={member.role} /> : null}
                <span className="inline-flex items-center rounded-md border border-line px-2 py-0.5 text-[10px] leading-4 text-ink">
                  {STATUS_LABEL[member.status] ?? member.status}
                </span>
              </div>
              {hasActions ? (
                <div className="flex flex-wrap items-center gap-2">
                  {actions.roles.length > 0 ? (
                    <>
                      <label htmlFor={`role-${member.id}`} className="sr-only">
                        Role of {member.displayName}
                      </label>
                      <select
                        id={`role-${member.id}`}
                        value={member.role}
                        disabled={rowBusy}
                        onChange={(event) => changeRole(member, event.target.value)}
                        className="h-10 rounded-md border border-line bg-panel px-2 text-base text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:opacity-60 sm:h-[29px] sm:text-xs"
                      >
                        <option value={member.role}>{roleLabel(member.role)}</option>
                        {actions.roles.map((role) => (
                          <option key={role} value={role}>
                            {roleLabel(role)}
                          </option>
                        ))}
                      </select>
                    </>
                  ) : null}
                  {actions.reactivate ? (
                    <button
                      type="button"
                      className={SECONDARY_BUTTON}
                      disabled={rowBusy}
                      aria-label={`Reactivate ${member.displayName}`}
                      onClick={() => reactivate(member)}
                    >
                      Reactivate
                    </button>
                  ) : null}
                  {actions.suspend ? (
                    <button
                      type="button"
                      className={SECONDARY_BUTTON}
                      disabled={rowBusy}
                      aria-label={`Suspend ${member.displayName}`}
                      onClick={() => setConfirm({ kind: "suspend", member })}
                    >
                      Suspend
                    </button>
                  ) : null}
                  {actions.remove ? (
                    <button
                      type="button"
                      className={DANGER_BUTTON}
                      disabled={rowBusy}
                      aria-label={`Remove ${member.displayName}`}
                      onClick={() => setConfirm({ kind: "remove", member })}
                    >
                      Remove
                    </button>
                  ) : null}
                </div>
              ) : null}
            </li>
          );
        })}
      </ul>

      <ConfirmDialog
        open={confirm !== null}
        title={confirm?.kind === "remove" ? `Remove ${confirm.member.displayName}?` : `Suspend ${confirm?.member.displayName ?? ""}?`}
        confirmLabel={confirm?.kind === "remove" ? "Remove" : "Suspend"}
        pendingLabel={confirm?.kind === "remove" ? "Removing…" : "Suspending…"}
        pending={confirmPending}
        error={confirmError}
        onConfirm={() => void confirmed()}
        onCancel={() => {
          setConfirm(null);
          setConfirmError(null);
        }}
      >
        {confirm?.kind === "remove"
          ? "They will lose access immediately. They will need a new invitation to rejoin."
          : "They will lose access immediately. You can reactivate them later."}
      </ConfirmDialog>

      <PasswordDialog open={reauthOpen} onConfirmed={() => void reauthenticated()} onCancel={cancelReauth} />
    </>
  );
}

function article(role: string): string {
  return /^[AEIOU]/.test(role) ? "an" : "a";
}
