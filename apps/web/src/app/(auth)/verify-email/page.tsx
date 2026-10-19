import type { Metadata } from "next";

import { AuthShell } from "@/components/auth/AuthShell";
import { VerifyEmail } from "@/components/auth/VerifyEmail";

export const metadata: Metadata = { title: "Verify your email" };

export default function VerifyEmailPage() {
  return (
    <AuthShell title="Verify your email.">
      <VerifyEmail />
    </AuthShell>
  );
}
