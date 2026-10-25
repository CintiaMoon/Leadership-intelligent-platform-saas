import { expect, test } from "vitest";

import { invitableRoles, invitationActions } from "./invitationActions";

test("an Owner may invite Admins, Agents and Viewers, never an Owner; an Admin only Agents and Viewers", () => {
  expect(invitableRoles("OWNER", "ACTIVE")).toEqual(["VIEWER", "AGENT", "ADMIN"]);
  expect(invitableRoles("ADMIN", "ACTIVE")).toEqual(["VIEWER", "AGENT"]);
  expect(invitableRoles("OWNER", "ACTIVE")).not.toContain("OWNER");
});

test("Agents, Viewers, unknown roles and companies that are not ACTIVE may invite nobody", () => {
  for (const role of ["AGENT", "VIEWER", "NOBODY", ""]) {
    expect(invitableRoles(role, "ACTIVE")).toEqual([]);
  }
  expect(invitableRoles("OWNER", "PENDING_DELETION")).toEqual([]);
});

test("resend and revoke follow the same limit as creating", () => {
  expect(invitationActions("OWNER", "ACTIVE", "ADMIN")).toEqual({ resend: true, revoke: true });
  expect(invitationActions("ADMIN", "ACTIVE", "AGENT")).toEqual({ resend: true, revoke: true });
  expect(invitationActions("ADMIN", "ACTIVE", "ADMIN")).toEqual({ resend: false, revoke: false });
  expect(invitationActions("VIEWER", "ACTIVE", "VIEWER")).toEqual({ resend: false, revoke: false });
  expect(invitationActions("OWNER", "PENDING_DELETION", "AGENT")).toEqual({ resend: false, revoke: false });
});
