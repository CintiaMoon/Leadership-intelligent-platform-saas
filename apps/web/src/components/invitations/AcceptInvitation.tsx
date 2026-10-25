"use client";

import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";

import { Notice, TextLink } from "@/components/auth/ui";
import { useFragmentToken } from "@/components/auth/useFragmentToken";
import { post, type CompanySummary } from "@/lib/api/client";
import { describeFailure } from "@/lib/api/messages";

import {
  INVITATION_NEXT,
  clearInvitationToken,
  loadInvitationToken,
  saveInvitationToken,
} from "./invitationToken";

/** What happened after the person pressed a button. `attempt` ties it to the link that was open at the time. */
type Outcome =
  | { kind: "joining"; attempt: number }
  | { kind: "joined"; attempt: number; company: CompanySummary | null }
  | { kind: "refused"; attempt: number; message: string }
  | { kind: "retry"; attempt: number; message: string };

const BUTTON =
  "inline-flex h-10 w-full items-center justify-center gap-2 rounded-md text-base font-medium focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent sm:h-[29px] sm:text-xs";
const PRIMARY = `${BUTTON} bg-ink text-page transition-opacity hover:opacity-90 disabled:cursor-wait disabled:opacity-60`;
const SECONDARY = `${BUTTON} border border-line bg-panel text-ink hover:bg-surface`;

const FALLBACK = "Something went wrong. Please try again.";

const INVALID =
  "This invitation link is invalid or has expired. Ask the person who invited you to send a new one.";

function isCompany(value: unknown): value is CompanySummary {
  const candidate = value as Partial<CompanySummary> | null;
  return typeof candidate?.name === "string" && typeof candidate?.role === "string";
}

function roleLabel(role: string): string {
  return role.charAt(0) + role.slice(1).toLowerCase();
}

/**
 * The invitation accept page (P1). It reads the token from the address fragment once, which also removes the fragment
 * from the address bar before any request is made, keeps it in this tab's session storage while the person signs in,
 * and sends it only in the body of the accept request. The company name is not shown before accepting: the page cannot
 * know whether the signed-in person is the one who was invited.
 */
export function AcceptInvitation({ signedIn }: { signedIn: boolean }) {
  const router = useRouter();
  const fragment = useFragmentToken();
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  useEffect(() => {
    if (fragment.status === "present") {
      saveInvitationToken(fragment.token);
    }
  }, [fragment]);

  // The fragment wins; with none, the person came back from signing in (or the link was incomplete).
  const attempt = fragment.status === "present" ? fragment.attempt : 0;
  const token =
    fragment.status === "present" ? fragment.token : fragment.status === "missing" ? loadInvitationToken() : null;
  const current = outcome?.attempt === attempt ? outcome : null;

  async function accept(token: string) {
    setOutcome({ kind: "joining", attempt });
    const result = await post("invitations/accept", { token });
    if (result.ok) {
      clearInvitationToken();
      // The invitation is used up either way; if the reply cannot be read the person is still told they joined.
      setOutcome({ kind: "joined", attempt, company: isCompany(result.data) ? result.data : null });
      return;
    }
    const code = result.problem?.code;
    if (code === "TOKEN_INVALID" || code === "CONFLICT") {
      // The invitation cannot be used by this person; keeping the token would only invite more failed attempts.
      clearInvitationToken();
      setOutcome({ kind: "refused", attempt, message: describeFailure(result, "invitation").form ?? INVALID });
      return;
    }
    if (result.status === 401) {
      router.replace(`/login?notice=expired&next=${INVITATION_NEXT}`);
      return;
    }
    setOutcome({ kind: "retry", attempt, message: describeFailure(result, "invitation").form || FALLBACK });
  }

  function decline() {
    clearInvitationToken();
    router.push("/account");
  }

  if (current?.kind === "refused") {
    return (
      <>
        <Notice tone="error">{current.message}</Notice>
        {signedIn ? (
          <TextLink href="/account" className="text-xs">
            Go to my profile
          </TextLink>
        ) : null}
      </>
    );
  }
  if (current?.kind === "joined") {
    return (
      <>
        <Notice tone="success">
          {current.company
            ? `You joined ${current.company.name} as ${roleLabel(current.company.role)}.`
            : "You joined the company."}
        </Notice>
        <TextLink href="/account" className="text-xs">
          Go to my profile
        </TextLink>
      </>
    );
  }
  if (fragment.status === "pending") {
    return <p className="text-xs text-muted">Opening your invitation…</p>;
  }
  if (!token) {
    return <Notice tone="error">{INVALID}</Notice>;
  }

  if (!signedIn) {
    return (
      <>
        <p className="text-xs leading-[18px] text-muted">
          You were invited to join a company. Use the address this invitation was sent to.
        </p>
        <TextLink href={`/login?next=${INVITATION_NEXT}`} className={SECONDARY}>
          Sign in
        </TextLink>
        <TextLink href={`/signup?next=${INVITATION_NEXT}`} className={SECONDARY}>
          Create an account
        </TextLink>
      </>
    );
  }

  const joining = current?.kind === "joining";
  return (
    <>
      {current?.kind === "retry" ? <Notice tone="error">{current.message}</Notice> : null}
      <p className="text-xs leading-[18px] text-muted">Join this company?</p>
      <button type="button" className={PRIMARY} disabled={joining} onClick={() => void accept(token)}>
        {joining ? "Joining…" : "Accept invitation"}
      </button>
      <button type="button" className={SECONDARY} disabled={joining} onClick={decline}>
        Not now
      </button>
    </>
  );
}
