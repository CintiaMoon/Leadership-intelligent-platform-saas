import { toTransferView, type TransferView } from "@/lib/ownership";

import { readApi } from "./api";
import type { BffConfig } from "./bff";
import { signInAgain } from "./session";

export type TransferLoad = { kind: "found"; transfer: TransferView } | { kind: "none" } | { kind: "error" };

/**
 * Reads the company's pending ownership transfer as the caller may see it. The API answers 404 to everyone who is not
 * the initiator or the recipient and to every caller when nothing is pending, so `none` covers all of those. A failed
 * read (the API is down or busy) is `error`, which the Ownership page must not mistake for "nothing pending". An ended
 * session goes to sign in.
 */
export async function readTransfer(incoming: Headers, config: BffConfig, companyId: string): Promise<TransferLoad> {
  const result = await readApi<Record<string, unknown>>(
    incoming,
    `/api/v1/companies/${companyId}/ownership-transfer`,
    config,
  );
  if (result.ok) {
    return { kind: "found", transfer: toTransferView(result.data) };
  }
  if (result.status === 401) {
    signInAgain();
  }
  return result.status === 404 ? { kind: "none" } : { kind: "error" };
}

/**
 * The same for the banner and the tab, where a missing banner is better than a broken page: anything but a transfer
 * that was found gives null.
 */
export async function loadTransfer(
  incoming: Headers,
  config: BffConfig,
  companyId: string,
): Promise<TransferView | null> {
  const result = await readTransfer(incoming, config, companyId);
  return result.kind === "found" ? result.transfer : null;
}
