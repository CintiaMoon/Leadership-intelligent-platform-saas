import type { Metadata } from "next";

import { AuthShell } from "@/components/auth/AuthShell";
import { ResetPasswordForm } from "@/components/auth/ResetPasswordForm";

export const metadata: Metadata = { title: "Choose a new password" };

export default function ResetPasswordPage() {
  return (
    <AuthShell title="Choose a new password.">
      <ResetPasswordForm />
    </AuthShell>
  );
}
