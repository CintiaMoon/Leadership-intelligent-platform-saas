import { act, renderHook } from "@testing-library/react";
import { expect, test, vi } from "vitest";

const refresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: vi.fn(), replace: vi.fn(), refresh }),
}));

import { useActionRunner } from "./useActionRunner";

test("a second change started while one is running does nothing, so a double submit sends one request", async () => {
  const { result } = renderHook(() => useActionRunner("member", []));
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  const request = vi.fn(async () => {
    await gate;
    return { ok: true as const, status: 200, data: null };
  });

  let first!: Promise<unknown>;
  let second!: Promise<{ done: boolean; error: string | null; succeeded: boolean }>;
  await act(async () => {
    first = result.current.run("m1", request, "done");
    second = result.current.run("m1", request, "done");
    release();
    await first;
  });

  expect(request).toHaveBeenCalledTimes(1);
  expect(await second).toEqual({ done: false, error: null, succeeded: false });
  expect(result.current.notice).toBe("done");
});

test("after one change finishes, the next one runs", async () => {
  const { result } = renderHook(() => useActionRunner("invitation", []));
  const request = vi.fn(async () => ({ ok: true as const, status: 200, data: null }));
  await act(async () => {
    await result.current.run("a", request, "one");
  });
  await act(async () => {
    await result.current.run("b", request, "two");
  });
  expect(request).toHaveBeenCalledTimes(2);
  expect(result.current.notice).toBe("two");
});
