"use client";

import { useId, useState, type FormEvent } from "react";

import { ConfirmDialog } from "@/components/app/ConfirmDialog";
import { PasswordDialog } from "@/components/app/PasswordDialog";
import { useActionRunner } from "@/components/app/useActionRunner";
import { DANGER_BUTTON, PRIMARY_BUTTON } from "@/components/app/buttons";
import { send } from "@/lib/api/client";
import type { Candidate, TransferView } from "@/lib/ownership";
import { roleLabel } from "@/lib/roles";

const SECTION = "mt-5 max-w-[560px] rounded-xl border border-line bg-surface p-5";
const HEADING = "font-serif text-base text-ink";
const TEXT = "mt-2 text-xs leading-[18px] text-muted [overflow-wrap:anywhere]";

/**
 * The Ownership page (SPEC-OWN, D-192): the Owner's view (ask a member, or see and cancel the pending request) or the
 * recipient's (accept). One component serves both, so the result of accepting ("You are now the Owner") survives the page
 * being read again as the Owner's view. What is shown only follows the data; the API decides every request, and a stale
 * answer ends in a 403 or 409 that the runner explains and refreshes (D-174, D-175).
 *
 * The password is asked for only when the API says the login is too old: the request is sent first, and sent once more
 * after the password was confirmed.
 */
export function OwnershipPanel({
  mode,
  companyId,
  companyName,
  companyStatus,
  transfer,
  candidates,
}: {
  mode: "owner" | "recipient" | "none";
  companyId: string;
  companyName: string;
  companyStatus: string;
  transfer: TransferView | null;
  candidates: Candidate[];
}) {
  const { notice, noticeRef, busy, run, reauthOpen, reauthenticated, cancelReauth } = useActionRunner(
    "transfer",
    transfer,
  );
  const selectId = useId();
  const choiceErrorId = useId();
  const [choice, setChoice] = useState("");
  const [choiceError, setChoiceError] = useState<string | null>(null);
  const [confirmCancel, setConfirmCancel] = useState(false);
  const [confirmPending, setConfirmPending] = useState(false);
  const [confirmError, setConfirmError] = useState<string | null>(null);

  const base = `/api/companies/${companyId}/ownership-transfers`;
  const pending = transfer?.status === "PENDING" ? transfer : null;
  const expired = transfer?.status === "EXPIRED" ? transfer : null;

  function ask(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const person = candidates.find((candidate) => candidate.id === choice);
    if (!person) {
      setChoiceError("Choose the member who should become the Owner.");
      return;
    }
    setChoiceError(null);
    void run(
      "ask",
      () => send("POST", base, { recipientMembershipId: person.id }),
      `${person.name} was asked to become the Owner. They have 7 days to accept.`,
    ).then((outcome) => {
      if (outcome.done) {
        setChoice("");
      }
    });
  }

  function accept() {
    if (!transfer) {
      return;
    }
    void run(
      transfer.id,
      () => send("POST", `${base}/${transfer.id}/accept`),
      `You are now the Owner of ${companyName}.`,
    );
  }

  async function cancelConfirmed() {
    if (!pending || confirmPending) {
      return;
    }
    setConfirmPending(true);
    setConfirmError(null);
    const outcome = await run(
      pending.id,
      () => send("POST", `${base}/${pending.id}/cancel`),
      `The request to ${pending.recipientName} was cancelled.`,
    );
    setConfirmPending(false);
    if (outcome.done) {
      setConfirmCancel(false);
    } else {
      setConfirmError(outcome.error);
    }
  }

  return (
    <>
      <div ref={noticeRef} tabIndex={-1} role="status" aria-live="polite" className={`outline-none ${notice ? "mt-5" : ""}`}>
        {notice ? (
          <p className="max-w-[560px] rounded-md border border-line bg-surface px-3 py-2 text-xs text-ink [overflow-wrap:anywhere]">
            {notice}
          </p>
        ) : null}
      </div>

      {mode === "owner" ? (
        <>
          {pending ? (
            <section aria-labelledby="pending" className={SECTION}>
              <h2 id="pending" className={HEADING}>
                Waiting for {pending.recipientName}
              </h2>
              <p className={TEXT}>
                {pending.recipientName} was asked to become the Owner. The request expires {pending.expires}. Until they
                accept, you stay the Owner.
              </p>
              <div className="mt-5">
                <button
                  type="button"
                  className={DANGER_BUTTON}
                  aria-disabled={busy === pending.id || undefined}
                  onClick={() => busy !== pending.id && setConfirmCancel(true)}
                >
                  Cancel the request
                </button>
              </div>
            </section>
          ) : companyStatus !== "ACTIVE" ? (
            <section aria-labelledby="blocked" className={SECTION}>
              <h2 id="blocked" className={HEADING}>
                Transfer ownership
              </h2>
              <p className={TEXT}>This company is scheduled for deletion. Its ownership cannot be transferred.</p>
            </section>
          ) : (
            <section aria-labelledby="transfer" className={SECTION}>
              <h2 id="transfer" className={HEADING}>
                Transfer ownership
              </h2>
              {expired ? (
                <p className="mt-3 rounded-md border border-line px-3 py-2 text-xs text-ink [overflow-wrap:anywhere]">
                  Expired. {expired.recipientName} did not accept in time. You are still the Owner.
                </p>
              ) : null}
              <p className={TEXT}>
                Ask an active member to become the Owner of {companyName}. You will become an Admin. The new Owner can
                remove you.
              </p>
              {candidates.length === 0 ? (
                <p className="mt-4 text-xs text-muted">There is no other active member to ask yet. Invite someone first.</p>
              ) : (
                <form onSubmit={ask} noValidate className="mt-4">
                  <label htmlFor={selectId} className="block text-[11px] font-semibold leading-4 text-ink">
                    New Owner
                  </label>
                  <select
                    id={selectId}
                    value={choice}
                    onChange={(event) => {
                      setChoice(event.target.value);
                      setChoiceError(null);
                    }}
                    aria-invalid={choiceError ? true : undefined}
                    aria-describedby={choiceError ? choiceErrorId : undefined}
                    className="mt-[5px] h-10 w-full rounded-md border border-line bg-panel px-2 text-base text-ink focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent sm:h-[29px] sm:text-xs"
                  >
                    <option value="">Choose a member</option>
                    {candidates.map((candidate) => (
                      <option key={candidate.id} value={candidate.id}>
                        {candidate.name} ({roleLabel(candidate.role)})
                      </option>
                    ))}
                  </select>
                  {choiceError ? (
                    <p id={choiceErrorId} role="alert" className="mt-2 text-xs text-danger">
                      {choiceError}
                    </p>
                  ) : null}
                  <div className="mt-5">
                    <button type="submit" className={PRIMARY_BUTTON} aria-disabled={busy === "ask" || undefined}>
                      {busy === "ask" ? "Sending…" : "Transfer ownership"}
                    </button>
                  </div>
                </form>
              )}
            </section>
          )}
        </>
      ) : null}

      {mode === "recipient" && transfer ? (
        pending ? (
          <section aria-labelledby="request" className={SECTION}>
            <h2 id="request" className={HEADING}>
              {pending.initiatorName} asked you to become the Owner
            </h2>
            <p className={TEXT}>
              If you accept, you become the Owner of {companyName} and {pending.initiatorName} becomes an Admin. The
              request expires {pending.expires}.
            </p>
            <div className="mt-5">
              <button
                type="button"
                className={PRIMARY_BUTTON}
                aria-disabled={busy === pending.id || undefined}
                onClick={() => busy !== pending.id && accept()}
              >
                {busy === pending.id ? "Accepting…" : "Accept ownership"}
              </button>
            </div>
          </section>
        ) : (
          <section aria-labelledby="gone" className={SECTION}>
            <h2 id="gone" className={HEADING}>
              This request has expired
            </h2>
            <p className={TEXT}>Ask {transfer.initiatorName} to send a new one if you still want to become the Owner.</p>
          </section>
        )
      ) : null}

      {mode === "none" ? (
        <section aria-labelledby="nothing" className={SECTION}>
          <h2 id="nothing" className={HEADING}>
            Nothing is waiting for you
          </h2>
          <p className={TEXT}>
            There is no request to become the Owner of {companyName}. If you were told to expect one, it may have been
            cancelled or have expired.
          </p>
        </section>
      ) : null}

      <ConfirmDialog
        open={confirmCancel}
        title={`Cancel the request to ${pending?.recipientName ?? "this member"}?`}
        confirmLabel="Cancel the request"
        pendingLabel="Cancelling…"
        pending={confirmPending}
        error={confirmError}
        onConfirm={() => void cancelConfirmed()}
        onCancel={() => {
          setConfirmCancel(false);
          setConfirmError(null);
        }}
      >
        They will no longer be able to accept it. You stay the Owner, and you can ask someone again.
      </ConfirmDialog>

      <PasswordDialog open={reauthOpen} onConfirmed={() => void reauthenticated()} onCancel={cancelReauth} />
    </>
  );
}
