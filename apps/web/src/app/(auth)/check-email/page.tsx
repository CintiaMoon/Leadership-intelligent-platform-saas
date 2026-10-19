import type { Metadata } from "next";

import { AuthShell } from "@/components/auth/AuthShell";
import { EmailRequestForm } from "@/components/auth/EmailRequestForm";
import { TextLink } from "@/components/auth/ui";

export const metadata: Metadata = { title: "Check your email" };

export default function CheckEmailPage() {
  return (
    <AuthShell
      title="Check your email."
      subtitle="If this address can be registered, we have sent a link to confirm it. The link works once and expires after 24 hours."
      footer={
        <>
          Already confirmed? <TextLink href="/login">Sign in</TextLink>
        </>
      }
    >
      <p className="text-xs leading-[18px] text-muted">Nothing arrived? Send the link again.</p>
      <EmailRequestForm
        path="verify-email/resend"
        submitLabel="Send a new link"
        pendingLabel="Sending…"
        doneMessage="If an unverified account exists for this address, we have sent a new verification email."
      />
    </AuthShell>
  );
}
