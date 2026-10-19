import type { Metadata } from "next";

import { AuthShell } from "@/components/auth/AuthShell";
import { EmailRequestForm } from "@/components/auth/EmailRequestForm";
import { TextLink } from "@/components/auth/ui";

export const metadata: Metadata = { title: "Reset your password" };

export default function ForgotPasswordPage() {
  return (
    <AuthShell
      title="Reset your password."
      subtitle="Enter your email and we will send reset instructions."
      footer={
        <>
          Remembered it? <TextLink href="/login">Sign in</TextLink>
        </>
      }
    >
      <EmailRequestForm
        path="password-reset/request"
        submitLabel="Send reset link"
        pendingLabel="Sending…"
        doneMessage="If an account exists for this address, we have sent password reset instructions. The link expires after 1 hour."
      />
    </AuthShell>
  );
}
