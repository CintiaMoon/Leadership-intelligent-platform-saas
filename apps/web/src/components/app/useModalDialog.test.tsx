import { cleanup, render } from "@testing-library/react";
import { afterEach, beforeEach, expect, test, vi } from "vitest";

import { useModalDialog } from "./useModalDialog";

const showModal = vi.fn(function (this: HTMLDialogElement) {
  this.setAttribute("open", "");
});
const close = vi.fn(function (this: HTMLDialogElement) {
  this.removeAttribute("open");
});

beforeEach(() => {
  showModal.mockClear();
  close.mockClear();
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", { value: showModal, configurable: true });
  Object.defineProperty(HTMLDialogElement.prototype, "close", { value: close, configurable: true });
});

afterEach(() => {
  cleanup();
});

function Probe({ open }: { open: boolean }) {
  const { ref, onClose } = useModalDialog(open);
  return <dialog ref={ref} onClose={onClose} aria-label="probe" />;
}

test("it opens the dialog when asked and closes it when not wanted any more", () => {
  const { rerender, container } = render(<Probe open />);
  const dialog = container.querySelector("dialog")!;
  expect(showModal).toHaveBeenCalledTimes(1);
  expect(dialog.open).toBe(true);

  rerender(<Probe open={false} />);
  expect(close).toHaveBeenCalledTimes(1);
  expect(dialog.open).toBe(false);
});

test("if the browser closes a dialog that is still wanted open, it is opened again", () => {
  const { container } = render(<Probe open />);
  const dialog = container.querySelector("dialog")!;

  // The browser closes it on its own (a second Escape while the first was ignored).
  dialog.removeAttribute("open");
  dialog.dispatchEvent(new Event("close"));

  expect(showModal).toHaveBeenCalledTimes(2);
  expect(dialog.open).toBe(true);
});

test("a close that we asked for is not undone", () => {
  const { rerender, container } = render(<Probe open />);
  rerender(<Probe open={false} />);
  container.querySelector("dialog")!.dispatchEvent(new Event("close"));
  expect(showModal).toHaveBeenCalledTimes(1);
});
