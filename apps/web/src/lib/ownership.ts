import type { MemberRow } from "@/components/companies/MembersList";

import { formatDateTimeUtc } from "./dates";

/**
 * What the Ownership page needs (SPEC-OWN, D-192). Every field is copied by name from the API's answer, so a field added
 * later never reaches the browser through the server-rendered page by accident. This decides only what to show: the API
 * checks every request again, and a stale page ends in a 403 or 409 that the page explains (D-175).
 */

export type TransferView = {
  id: string;
  /** PENDING, or EXPIRED when the transfer ran past its expiry before the sweep marked it. */
  status: string;
  initiatorName: string;
  recipientName: string;
  youAreInitiator: boolean;
  youAreRecipient: boolean;
  /** Already formatted on the server, in UTC, so the server and the browser print the same text. */
  expires: string;
};

export type Candidate = { id: string; name: string; role: string };

export function toTransferView(dto: Record<string, unknown>): TransferView {
  return {
    id: String(dto.id),
    status: String(dto.status),
    initiatorName: String(dto.initiatorName),
    recipientName: String(dto.recipientName),
    youAreInitiator: dto.youAreInitiator === true,
    youAreRecipient: dto.youAreRecipient === true,
    expires: formatDateTimeUtc(String(dto.expiresAt)),
  };
}

/** The members the Owner may ask: ACTIVE, not the Owner themselves. The API still refuses an ineligible account (409). */
export function toCandidates(members: MemberRow[]): Candidate[] {
  return members
    .filter((member) => member.status === "ACTIVE" && !member.self && member.role !== "OWNER")
    .map((member) => ({ id: member.id, name: member.displayName, role: member.role }));
}

/** Which view of the Ownership page a person gets: the Owner's, a member's with a request to answer, or the plain one. */
export function ownershipView(role: string, transfer: TransferView | null): "owner" | "recipient" | "none" {
  if (role === "OWNER") {
    return "owner";
  }
  return transfer?.youAreRecipient ? "recipient" : "none";
}

/** Whether a request is waiting for this person to accept it (the banner and the tab). */
export function waitingForYou(transfer: TransferView | null): boolean {
  return transfer !== null && transfer.youAreRecipient && transfer.status === "PENDING";
}
