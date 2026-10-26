import { toTransferView, type TransferView } from "@/lib/ownership";

import { readApi } from "./api";
import type { BffConfig } from "./bff";
import { signInAgain } from "./session";

/**
 * The company's pending ownership transfer as the caller may see it, or null. The API answers 404 to everyone who is not
 * the initiator or the recipient and to every caller when nothing is pending, so null covers all of those; any other
 * failure also gives null, because a missing banner is better than a broken page. An ended session goes to sign in.
 */
export async function loadTransfer(
  incoming: Headers,
  config: BffConfig,
  companyId: string,
): Promise<TransferView | null> {
  const result = await readApi<Record<string, unknown>>(
    incoming,
    `/api/v1/companies/${companyId}/ownership-transfer`,
    config,
  );
  if (result.ok) {
    return toTransferView(result.data);
  }
  if (result.status === 401) {
    signInAgain();
  }
  return null;
}
