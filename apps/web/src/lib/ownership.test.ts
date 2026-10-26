import { expect, test } from "vitest";

import type { MemberRow } from "@/components/companies/MembersList";

import { ownershipView, toCandidates, toTransferView, waitingForYou, type TransferView } from "./ownership";

const member = (over: Partial<MemberRow>): MemberRow => ({
  id: "m1",
  displayName: "Bo Chen",
  email: "bo@example.com",
  role: "ADMIN",
  status: "ACTIVE",
  joined: "Oct 14, 2026",
  self: false,
  ...over,
});

const view = (over: Partial<TransferView> = {}): TransferView => ({
  id: "t1",
  status: "PENDING",
  initiatorName: "Ana Silva",
  recipientName: "Bo Chen",
  youAreInitiator: false,
  youAreRecipient: true,
  expires: "Nov 2, 2026, 10:00 AM UTC",
  ...over,
});

test("only the named fields of the API answer reach the page, and the expiry is formatted in UTC", () => {
  const dto = {
    id: "t1",
    status: "PENDING",
    initiatorName: "Ana",
    recipientName: "Bo",
    youAreInitiator: true,
    youAreRecipient: false,
    createdAt: "2026-10-26T10:00:00Z",
    expiresAt: "2026-11-02T10:00:00Z",
    userId: "should-never-appear",
    email: "nobody@example.com",
  };
  const result = toTransferView(dto);
  expect(Object.keys(result).sort()).toEqual(
    ["expires", "id", "initiatorName", "recipientName", "status", "youAreInitiator", "youAreRecipient"].sort(),
  );
  expect(result.expires).toBe("Nov 2, 2026, 10:00 AM UTC");
  expect(JSON.stringify(result)).not.toContain("should-never-appear");
});

test("the Owner may ask active members other than themselves, never a suspended one or the Owner", () => {
  const rows = [
    member({ id: "o", role: "OWNER", self: true }),
    member({ id: "p", displayName: "Fay Gill", role: "OWNER" }),
    member({ id: "a", displayName: "Bo Chen" }),
    member({ id: "g", displayName: "Cy Dube", role: "AGENT" }),
    member({ id: "s", displayName: "Di Eze", role: "VIEWER", status: "SUSPENDED" }),
    member({ id: "r", displayName: "Ed Fox", status: "REMOVED" }),
  ];
  expect(toCandidates(rows)).toEqual([
    { id: "a", name: "Bo Chen", role: "ADMIN" },
    { id: "g", name: "Cy Dube", role: "AGENT" },
  ]);
});

test("the Owner gets the Owner view; another role gets the request view only when it is addressed to them", () => {
  expect(ownershipView("OWNER", null)).toBe("owner");
  expect(ownershipView("OWNER", view({ youAreRecipient: false, youAreInitiator: true }))).toBe("owner");
  for (const role of ["ADMIN", "AGENT", "VIEWER"]) {
    expect(ownershipView(role, view())).toBe("recipient");
    expect(ownershipView(role, null)).toBe("none");
    expect(ownershipView(role, view({ youAreRecipient: false }))).toBe("none");
  }
});

test("a request is waiting only while it is pending and addressed to the person", () => {
  expect(waitingForYou(view())).toBe(true);
  expect(waitingForYou(view({ status: "EXPIRED" }))).toBe(false);
  expect(waitingForYou(view({ youAreRecipient: false }))).toBe(false);
  expect(waitingForYou(null)).toBe(false);
});
