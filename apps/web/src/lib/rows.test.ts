import { expect, test } from "vitest";

import { toInvitationRows, toMemberRows } from "./rows";

test("member rows carry only the named fields, whatever else the API sends", () => {
  const rows = toMemberRows([
    {
      id: "m1",
      displayName: "Ana",
      email: "ana@example.com",
      role: "OWNER",
      status: "ACTIVE",
      joinedAt: "2026-10-12T09:30:00Z",
      self: true,
      userId: "secret-user-id",
      passwordHash: "secret-hash",
    },
  ]);
  expect(rows).toEqual([
    { id: "m1", displayName: "Ana", email: "ana@example.com", role: "OWNER", status: "ACTIVE", joined: "Oct 12, 2026", self: true },
  ]);
  expect(JSON.stringify(rows)).not.toMatch(/secret/);
});

test("invitation rows carry no token, hash or link even if the API ever sent one", () => {
  const rows = toInvitationRows([
    {
      id: "i1",
      email: "bo@example.com",
      role: "AGENT",
      status: "PENDING",
      expiresAt: "2026-11-01T14:03:00Z",
      createdAt: "2026-10-25T14:03:00Z",
      emailSent: true,
      token: "secret-token",
      tokenHash: "secret-hash",
      link: "https://app/invitations/accept#token=secret-token",
    },
  ]);
  expect(rows).toEqual([{ id: "i1", email: "bo@example.com", role: "AGENT", expires: "Nov 1, 2026, 2:03 PM UTC", emailSent: true }]);
  expect(JSON.stringify(rows)).not.toMatch(/secret|token/i);
});

test("a missing flag is false, never true", () => {
  expect(toMemberRows([{ id: "m", displayName: "A", email: "a@b", role: "AGENT", status: "ACTIVE", joinedAt: "2026-10-12T00:00:00Z" }])[0].self).toBe(false);
  expect(toInvitationRows([{ id: "i", email: "a@b", role: "AGENT", expiresAt: "2026-11-01T00:00:00Z" }])[0].emailSent).toBe(false);
});
