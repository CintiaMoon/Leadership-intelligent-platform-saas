"use client";

import { useState } from "react";

import { ConfirmDialog } from "@/components/app/ConfirmDialog";
import { PasswordDialog } from "@/components/app/PasswordDialog";
import { useActionRunner } from "@/components/app/useActionRunner";
import { DANGER_BUTTON, SECONDARY_BUTTON } from "@/components/app/buttons";
import { RoleBadge } from "@/components/companies/CompanyList";
import { send } from "@/lib/api/client";
import { invitationActions } from "@/lib/invitationActions";

export type InvitationRow = {
  id: string;
  email: string;
  role: string;
  /** Already formatted on the server, so the server and the browser print the same text. */
  expires: string;
  emailSent: boolean;
};

/**
 * The pending invitations of one company (P2, pending tab). The list never holds a token or a link: the API does not
 * return one. Resend and revoke are offered only where the caller may use them; the API decides every request.
 */
export function InvitationsList({
  companyId,
  callerRole,
  companyStatus,
  invitations,
}: {
  companyId: string;
  callerRole: string;
  companyStatus: string;
  invitations: InvitationRow[];
}) {
  const { notice, noticeRef, busy, run, reauthOpen, reauthenticated, cancelReauth } = useActionRunner("invitation", invitations);
  const [revoking, setRevoking] = useState<InvitationRow | null>(null);
  const [revokePending, setRevokePending] = useState(false);
  const [revokeError, setRevokeError] = useState<string | null>(null);

  const base = `/api/companies/${companyId}/invitations`;

  function resend(invitation: InvitationRow) {
    void run(
      invitation.id,
      () => send("POST", `${base}/${invitation.id}/resend`),
      `The invitation to ${invitation.email} was sent again.`,
    );
  }

  async function revoke() {
    if (!revoking || revokePending) {
      return;
    }
    const target = revoking;
    setRevokePending(true);
    setRevokeError(null);
    const outcome = await run(target.id, () => send("DELETE", `${base}/${target.id}`), `The invitation to ${target.email} was revoked.`);
    setRevokePending(false);
    if (outcome.done) {
      setRevoking(null);
    } else {
      setRevokeError(outcome.error);
    }
  }

  return (
    <>
      <div ref={noticeRef} tabIndex={-1} role="status" aria-live="polite" className={`outline-none ${notice ? "mb-4" : ""}`}>
        {notice ? <p className="rounded-md border border-line bg-surface px-3 py-2 text-xs text-ink">{notice}</p> : null}
      </div>
      {invitations.length === 0 ? (
        <p className="rounded-xl border border-line bg-surface p-5 text-xs text-muted">No pending invitations.</p>
      ) : (
        <ul className="flex flex-col gap-2" aria-label="Pending invitations">
          {invitations.map((invitation) => {
            const actions = invitationActions(callerRole, companyStatus, invitation.role);
            const rowBusy = busy === invitation.id;
            return (
              <li
                key={invitation.id}
                className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3 rounded-xl border border-line bg-surface px-5 py-3"
              >
                <div className="min-w-0 flex-1 basis-[220px]">
                  <p className="[overflow-wrap:anywhere] text-xs font-semibold text-ink">{invitation.email}</p>
                  <p className="mt-1 text-[11px] text-muted">Expires {invitation.expires}</p>
                </div>
                <div className="flex items-center gap-2">
                  <RoleBadge role={invitation.role} />
                  {invitation.emailSent ? null : (
                    <span className="inline-flex items-center rounded-md border border-line px-2 py-0.5 text-[10px] leading-4 text-ink">
                      Sending
                    </span>
                  )}
                </div>
                {actions.resend || actions.revoke ? (
                  <div className="flex flex-wrap items-center gap-2">
                    {actions.resend ? (
                      <button
                        type="button"
                        className={SECONDARY_BUTTON}
                        aria-disabled={rowBusy || undefined}
                        aria-label={`Resend the invitation to ${invitation.email}`}
                        onClick={() => !rowBusy && resend(invitation)}
                      >
                        Resend
                      </button>
                    ) : null}
                    {actions.revoke ? (
                      <button
                        type="button"
                        className={DANGER_BUTTON}
                        aria-disabled={rowBusy || undefined}
                        aria-label={`Revoke the invitation to ${invitation.email}`}
                        onClick={() => !rowBusy && setRevoking(invitation)}
                      >
                        Revoke
                      </button>
                    ) : null}
                  </div>
                ) : null}
              </li>
            );
          })}
        </ul>
      )}

      <ConfirmDialog
        open={revoking !== null}
        title={`Revoke the invitation to ${revoking?.email ?? ""}?`}
        confirmLabel="Revoke"
        pendingLabel="Revoking…"
        pending={revokePending}
        error={revokeError}
        onConfirm={() => void revoke()}
        onCancel={() => {
          setRevoking(null);
          setRevokeError(null);
        }}
      >
        The link in the email stops working at once. You can invite the address again later.
      </ConfirmDialog>

      <PasswordDialog open={reauthOpen} onConfirmed={() => void reauthenticated()} onCancel={cancelReauth} />
    </>
  );
}
