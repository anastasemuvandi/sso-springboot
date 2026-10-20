import "@testing-library/jest-dom/vitest";
import { afterAll, afterEach, beforeAll } from "vitest";
import { cleanup } from "@testing-library/react";

import i18n from "@/i18n";
import { server } from "./server";

beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
  void i18n.changeLanguage("en");
});
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => server.close());

// Radix primitives use these browser APIs that jsdom lacks.
class RO {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver ??= RO as unknown as typeof ResizeObserver;
if (!Element.prototype.hasPointerCapture) {
  Element.prototype.hasPointerCapture = () => false;
  Element.prototype.releasePointerCapture = () => {};
  Element.prototype.scrollIntoView = () => {};
}
