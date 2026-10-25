import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";

import { expect, test } from "vitest";

/**
 * The layout checks the session once, but Next.js does not render a layout again when a person moves between pages, so
 * every page behind the shell has to check the session and read its data itself (F1 security review). This test fails
 * when a page is added under `(app)` without doing so.
 */

function pages(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) {
      return pages(full);
    }
    return name === "page.tsx" ? [full] : [];
  });
}

const root = join(__dirname, "(app)");

test("every page behind the shell checks the session itself", () => {
  const found = pages(root);
  expect(found.length).toBeGreaterThanOrEqual(5);
  const unchecked = found.filter((file) => {
    const source = readFileSync(file, "utf8");
    return !/\brequireSession\(/.test(source) && !/\bloadCompany\(/.test(source);
  });
  expect(unchecked).toEqual([]);
});

test("the shell itself requires a session", () => {
  expect(readFileSync(join(root, "layout.tsx"), "utf8")).toMatch(/\brequireSession\(/);
});

test("loadCompany checks the session before it reads anything", () => {
  const source = readFileSync(join(__dirname, "..", "lib", "server", "company.ts"), "utf8");
  expect(source.indexOf("requireSession(")).toBeGreaterThan(-1);
  expect(source.indexOf("requireSession(")).toBeLessThan(source.indexOf("readApi<"));
});
