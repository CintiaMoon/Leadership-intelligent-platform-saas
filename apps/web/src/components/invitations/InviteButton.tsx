"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useId, useRef, useState, type FormEvent } from "react";

import { PasswordDialog } from "@/components/app/PasswordDialog";
import { useModalDialog } from "@/components/app/useModalDialog";
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from "@/components/app/buttons";
import { send, type ApiResult } from "@/lib/api/client";
import { describeFailure, type FormError } from "@/lib/api/messages";
import { invitableRoles } from "@/lib/invitationActions";
import { roleLabel } from "@/lib/roles";

/**
 * "Invite someone" and its dialog (P3). One address and one role the caller may give. The dialog never shows or asks for
 * a token. Inviting an Admin with a stale login opens the password dialog and sends the same request once more (D-174).
 * The capacity warning of the brief waits for the plan data of slice 006 (AC-INV-26); creating is never capped here.
 */
export function InviteButton({
  companyId,
  callerRole,
  companyStatus,
  onPendingPage = false,
}: {
  companyId: string;
  callerRole: string;
  companyStatus: string;
  onPendingPage?: boolean;
}) {
  const router = useRouter();
  const roles = invitableRoles(callerRole, companyStatus);
  const formRef = useRef<HTMLFormElement>(null);
  const titleId = useId();
  const emailId = useId();
  const roleId = useId();
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<FormError | null>(null);
  const [reauth, setReauth] = useState<{ email: string; role: string } | null>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);
  const { ref: dialogRef, onClose } = useModalDialog(open);

  if (roles.length === 0) {
    return null;
  }

  function close() {
    setOpen(false);
    setError(null);
    formRef.current?.reset();
  }

  /** Sends the invitation; `again` is true on the one retry after the password was confirmed. */
  async function submit(email: string, role: string, again = false) {
    setPending(true);
    setError(null);
    const result: ApiResult = await send("POST", `/api/companies/${companyId}/invitations`, { email, role });
    setPending(false);
    if (result.ok) {
      close();
      setSentTo(email);
      router.refresh();
      return;
    }
    if (result.status === 401) {
      router.replace("/login?notice=expired");
      return;
    }
    if (result.status === 403 && result.problem?.code === "REAUTHENTICATION_REQUIRED" && !again) {
      setReauth({ email, role });
      return;
    }
    const failure = describeFailure(result, "invite");
    if (result.status === 403 || result.status === 404) {
      router.refresh();
    }
    setError(failure);
  }

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending) {
      return;
    }
    const data = new FormData(event.currentTarget);
    void submit(String(data.get("email") ?? "").trim(), String(data.get("role") ?? ""));
  }

  return (
    <div className="flex flex-col items-end gap-2">
      <button
        type="button"
        className={PRIMARY_BUTTON}
        onClick={() => {
          setSentTo(null);
          setOpen(true);
        }}
      >
        Invite someone
      </button>
      <div role="status" aria-live="polite" className="max-w-[320px] text-right text-[11px] text-muted">
        {sentTo ? (
          <>
            Invitation sent to {sentTo}.
            {onPendingPage ? null : (
              <>
                {" "}
                <Link
                  href={`/companies/${companyId}/invitations`}
                  className="rounded-sm font-medium text-ink underline underline-offset-2 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
                >
                  View pending invitations
                </Link>
              </>
            )}
          </>
        ) : null}
      </div>

      <dialog
        ref={dialogRef}
        onClose={onClose}
        aria-labelledby={titleId}
        aria-describedby={`${titleId}-intro`}
        onCancel={(event) => {
          event.preventDefault();
          if (!pending) {
            close();
          }
        }}
        className="m-auto w-[calc(100%-2rem)] max-w-[380px] rounded-xl border border-line bg-surface p-5 text-left text-ink backdrop:bg-black/60"
      >
        <form ref={formRef} onSubmit={onSubmit} noValidate>
          <h2 id={titleId} className="font-serif text-base text-ink">
            Invite someone
          </h2>
          <p id={`${titleId}-intro`} className="mt-1 text-[11px] leading-4 text-muted">
            We email them a link that works for 7 days. They need an account with this address to accept.
          </p>
          <label htmlFor={emailId} className="mt-4 block text-[11px] font-semibold leading-4 text-ink">
            Email address
          </label>
          <input
            id={emailId}
            name="email"
            type="email"
            autoComplete="off"
            maxLength={254}
            required
            autoFocus
            aria-invalid={error?.fields.email ? true : undefined}
            aria-describedby={error?.fields.email ? `${emailId}-error` : undefined}
            className={`mt-[5px] h-10 w-full rounded-md border bg-panel px-2.5 text-base text-ink outline-none focus-visible:border-accent focus-visible:ring-2 focus-visible:ring-accent/60 sm:h-[29px] sm:text-xs ${
              error?.fields.email ? "border-danger" : "border-line"
            }`}
          />
          {error?.fields.email ? (
            <p id={`${emailId}-error`} className="mt-1 text-[11px] leading-4 text-danger">
              {error.fields.email}
            </p>
          ) : null}
          <label htmlFor={roleId} className="mt-3.5 block text-[11px] font-semibold leading-4 text-ink">
            Role
          </label>
          <select
            id={roleId}
            name="role"
            defaultValue={roles[0]}
            className="mt-[5px] h-10 w-full rounded-md border border-line bg-panel px-2 text-base text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent sm:h-[29px] sm:text-xs"
          >
            {roles.map((role) => (
              <option key={role} value={role}>
                {roleLabel(role)}
              </option>
            ))}
          </select>
          {error?.form ? (
            <p role="alert" className="mt-3 rounded-md border border-danger/50 px-3 py-2 text-xs text-danger">
              {error.form}
            </p>
          ) : null}
          <div className="mt-5 flex justify-end gap-2">
            <button type="button" className={SECONDARY_BUTTON} disabled={pending} onClick={close}>
              Cancel
            </button>
            <button type="submit" className={PRIMARY_BUTTON} disabled={pending}>
              {pending ? "Sending…" : "Send invitation"}
            </button>
          </div>
        </form>
      </dialog>

      <PasswordDialog
        open={reauth !== null}
        onConfirmed={() => {
          const pendingRequest = reauth;
          setReauth(null);
          if (pendingRequest) {
            void submit(pendingRequest.email, pendingRequest.role, true);
          }
        }}
        onCancel={() => setReauth(null)}
      />
    </div>
  );
}
