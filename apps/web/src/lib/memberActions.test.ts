import { expect, test } from "vitest";

import { mayManageMembers, memberActions } from "./memberActions";

const member = (role: string, status = "ACTIVE", self = false) => ({ role, status, self });

test("an Owner may change, suspend and remove every other member, with every role but their own offered", () => {
  expect(memberActions("OWNER", "ACTIVE", member("ADMIN"))).toEqual({
    roles: ["AGENT", "VIEWER"],
    suspend: true,
    reactivate: false,
    remove: true,
  });
  expect(memberActions("OWNER", "ACTIVE", member("VIEWER")).roles).toEqual(["ADMIN", "AGENT"]);
  expect(memberActions("OWNER", "ACTIVE", member("AGENT")).roles).toEqual(["ADMIN", "VIEWER"]);
});

test("an Admin acts only on Agents and Viewers and can only assign those roles", () => {
  expect(memberActions("ADMIN", "ACTIVE", member("AGENT"))).toEqual({
    roles: ["VIEWER"],
    suspend: true,
    reactivate: false,
    remove: true,
  });
  expect(memberActions("ADMIN", "ACTIVE", member("VIEWER")).roles).toEqual(["AGENT"]);
  for (const target of ["OWNER", "ADMIN"]) {
    expect(memberActions("ADMIN", "ACTIVE", member(target))).toEqual({ roles: [], suspend: false, reactivate: false, remove: false });
  }
});

test("a suspended member can only be reactivated or removed", () => {
  expect(memberActions("OWNER", "ACTIVE", member("AGENT", "SUSPENDED"))).toEqual({
    roles: [],
    suspend: false,
    reactivate: true,
    remove: true,
  });
  expect(memberActions("ADMIN", "ACTIVE", member("VIEWER", "SUSPENDED")).reactivate).toBe(true);
});

test("nobody gets actions on themselves, on the Owner, or on a removed member", () => {
  const none = { roles: [], suspend: false, reactivate: false, remove: false };
  expect(memberActions("OWNER", "ACTIVE", member("OWNER", "ACTIVE", true))).toEqual(none);
  expect(memberActions("ADMIN", "ACTIVE", member("ADMIN", "ACTIVE", true))).toEqual(none);
  expect(memberActions("OWNER", "ACTIVE", member("AGENT", "ACTIVE", true))).toEqual(none);
  expect(memberActions("OWNER", "ACTIVE", member("OWNER"))).toEqual(none);
  expect(memberActions("OWNER", "ACTIVE", member("AGENT", "REMOVED"))).toEqual(none);
});

test("Agents, Viewers and unknown roles get nothing", () => {
  for (const caller of ["AGENT", "VIEWER", "SOMETHING_ELSE", ""]) {
    expect(memberActions(caller, "ACTIVE", member("VIEWER")).remove).toBe(false);
    expect(mayManageMembers(caller)).toBe(false);
  }
  expect(mayManageMembers("OWNER")).toBe(true);
  expect(mayManageMembers("ADMIN")).toBe(true);
});

test("a company that is not ACTIVE is read-only for everyone", () => {
  expect(memberActions("OWNER", "PENDING_DELETION", member("AGENT"))).toEqual({
    roles: [],
    suspend: false,
    reactivate: false,
    remove: false,
  });
});
