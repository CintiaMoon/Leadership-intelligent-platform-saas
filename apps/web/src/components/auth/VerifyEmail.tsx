"use client";

import { useEffect, useRef, useState } from "react";

import { post } from "@/lib/api/client";
import { describeFailure } from "@/lib/api/messages";

import { EmailRequestForm } from "./EmailRequestForm";
import { Notice, TextLink } from "./ui";
import { useFragmentToken } from "./useFragmentToken";

type Outcome =
  | { kind: "working" }
  | { kind: "verified" }
  | { kind: "invalid" }
  | { kind: "failed"; message: string };

/** Verifies the address as soon as the page opens; the token never leaves memory except in that one request. */
export function VerifyEmail() {
  const fragment = useFragmentToken();
  const startedFor = useRef(0);
  const [outcome, setOutcome] = useState<Outcome>({ kind: "working" });

  useEffect(() => {
    if (fragment.status !== "present" || startedFor.current === fragment.attempt) {
      return;
    }
    startedFor.current = fragment.attempt;
    // A new link opened in the same tab starts a new attempt.
    setOutcome({ kind: "working" });
    void post("verify-email", { token: fragment.token }).then((result) => {
      if (result.ok) {
        setOutcome({ kind: "verified" });
      } else if (result.problem?.code === "TOKEN_INVALID") {
        setOutcome({ kind: "invalid" });
      } else {
        setOutcome({ kind: "failed", message: describeFailure(result).form ?? "" });
      }
    });
  }, [fragment]);

  if (fragment.status === "missing") {
    return <Missing text="This link is incomplete. Open the link from your email again." />;
  }
  if (outcome.kind === "verified") {
    return (
      <>
        <Notice tone="success">Your email address is verified.</Notice>
        <TextLink href="/login?notice=verified" className="text-xs">
          Continue to sign in
        </TextLink>
      </>
    );
  }
  if (outcome.kind === "invalid") {
    return <Missing text="This link is invalid or has expired." />;
  }
  if (outcome.kind === "failed") {
    return <Notice tone="error">{outcome.message}</Notice>;
  }
  return <p className="text-xs text-muted">Verifying your email address…</p>;
}

function Missing({ text }: { text: string }) {
  return (
    <>
      <Notice tone="error">{text}</Notice>
      <p className="text-xs text-muted">We can send you a new link.</p>
      <EmailRequestForm
        path="verify-email/resend"
        submitLabel="Send a new link"
        pendingLabel="Sending…"
        doneMessage="If an unverified account exists for this address, we have sent a new verification email."
      />
    </>
  );
}
