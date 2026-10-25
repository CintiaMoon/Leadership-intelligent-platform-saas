import type { InvitationRow } from "@/components/invitations/InvitationsList";
import type { MemberRow } from "@/components/companies/MembersList";

import { formatDate, formatDateTimeUtc } from "./dates";

/**
 * What the pages hand to the client components. Each field is copied by name, so a field the API adds later (an id of
 * a user, a token hash, a link) never reaches the browser through the server-rendered payload by accident.
 */

export function toMemberRows(dtos: Record<string, unknown>[]): MemberRow[] {
  return dtos.map((dto) => ({
    id: String(dto.id),
    displayName: String(dto.displayName),
    email: String(dto.email),
    role: String(dto.role),
    status: String(dto.status),
    joined: formatDate(String(dto.joinedAt)),
    self: dto.self === true,
  }));
}

export function toInvitationRows(dtos: Record<string, unknown>[]): InvitationRow[] {
  return dtos.map((dto) => ({
    id: String(dto.id),
    email: String(dto.email),
    role: String(dto.role),
    expires: formatDateTimeUtc(String(dto.expiresAt)),
    emailSent: dto.emailSent === true,
  }));
}
