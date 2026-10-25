import type { MemberRole } from "./memberActions";

/**
 * What the invitation screens offer (SPEC-INV, mirrors `InvitationRules`). Only decides what to show: the API checks
 * every request, and a stale answer here ends in a 403 or 409 that the page explains (D-175).
 */

/** The roles the caller may put in a new invitation. Nobody invites an Owner. */
export function invitableRoles(callerRole: string, companyStatus: string): MemberRole[] {
  if (companyStatus !== "ACTIVE") {
    return [];
  }
  if (callerRole === "OWNER") {
    return ["VIEWER", "AGENT", "ADMIN"];
  }
  if (callerRole === "ADMIN") {
    return ["VIEWER", "AGENT"];
  }
  return [];
}

export type InvitationActions = { resend: boolean; revoke: boolean };

/** Resend and revoke follow the same limit as creating: an Admin cannot touch an invitation for an Admin. */
export function invitationActions(callerRole: string, companyStatus: string, invitedRole: string): InvitationActions {
  const allowed = invitableRoles(callerRole, companyStatus).includes(invitedRole as MemberRole);
  return { resend: allowed, revoke: allowed };
}
