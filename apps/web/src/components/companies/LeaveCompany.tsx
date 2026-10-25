"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { ConfirmDialog } from "@/components/app/ConfirmDialog";
import { DANGER_BUTTON } from "@/components/app/buttons";
import { send } from "@/lib/api/client";
import { describeFailure } from "@/lib/api/messages";

/** Leave a company (P6). The Owner cannot: ownership has to move first, and the API refuses it too. */
export function LeaveCompany({ companyId, companyName, role }: { companyId: string; companyName: string; role: string }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (role === "OWNER") {
    return <p className="text-xs text-muted">Transfer ownership first. An Owner cannot leave the company.</p>;
  }

  async function leave() {
    if (pending) {
      return;
    }
    setPending(true);
    setError(null);
    const result = await send("POST", `/api/companies/${companyId}/leave`);
    if (result.ok) {
      router.replace("/companies");
      router.refresh();
      return;
    }
    if (result.status === 401) {
      router.replace("/login?notice=expired");
      return;
    }
    setError(describeFailure(result, "company").form);
    setPending(false);
  }

  return (
    <>
      <button type="button" className={DANGER_BUTTON} onClick={() => setOpen(true)}>
        Leave company
      </button>
      <ConfirmDialog
        open={open}
        title={`Leave ${companyName}?`}
        confirmLabel="Leave company"
        pendingLabel="Leaving…"
        pending={pending}
        error={error}
        onConfirm={leave}
        onCancel={() => {
          setOpen(false);
          setError(null);
        }}
      >
        You will lose access immediately. You can only rejoin if someone invites you again.
      </ConfirmDialog>
    </>
  );
}
