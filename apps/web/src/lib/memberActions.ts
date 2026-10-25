/**
 * Which actions the Members page offers for one member (SPEC-MEM, mirrors `MembershipChanges` and the matrix in
 * `CompanyPolicy`). This only decides what to show: the API checks every request again, and a stale answer here just
 * ends in a 403 or 409 that the page explains (D-175).
 */

export type MemberRole = "OWNER" | "ADMIN" | "AGENT" | "VIEWER";
export type MemberStatus = "ACTIVE" | "SUSPENDED" | "REMOVED";

export type MemberActions = {
  /** Roles the member can be changed to (never Owner, never the current role). Empty means no role control. */
  roles: MemberRole[];
  suspend: boolean;
  reactivate: boolean;
  remove: boolean;
};

const NONE: MemberActions = { roles: [], suspend: false, reactivate: false, remove: false };

export function memberActions(
  callerRole: string,
  companyStatus: string,
  member: { role: string; status: string; self: boolean },
): MemberActions {
  // Pending deletion: only reading is left for everybody (SPEC-TEN).
  if (companyStatus !== "ACTIVE") {
    return NONE;
  }
  // Nobody acts on their own membership here (leaving is separate) and the Owner is never changed.
  if (member.self || member.role === "OWNER" || member.status === "REMOVED") {
    return NONE;
  }
  const targetIsAgentOrViewer = member.role === "AGENT" || member.role === "VIEWER";
  if (callerRole === "ADMIN" && !targetIsAgentOrViewer) {
    return NONE;
  }
  if (callerRole !== "OWNER" && callerRole !== "ADMIN") {
    return NONE;
  }
  const assignable: MemberRole[] = callerRole === "OWNER" ? ["ADMIN", "AGENT", "VIEWER"] : ["AGENT", "VIEWER"];
  const active = member.status === "ACTIVE";
  return {
    roles: active ? assignable.filter((role) => role !== member.role) : [],
    suspend: active,
    reactivate: member.status === "SUSPENDED",
    remove: true,
  };
}

/** Whether the person may use the Members page at all (the API refuses everyone else with 403). */
export function mayManageMembers(role: string): boolean {
  return role === "OWNER" || role === "ADMIN";
}
