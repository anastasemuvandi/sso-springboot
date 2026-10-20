import "@testing-library/jest-dom/vitest";
import { webcrypto } from "node:crypto";

// jsdom has no SubtleCrypto; use Node's Web Crypto (same API as browsers).
if (!globalThis.crypto?.subtle) {
  Object.defineProperty(globalThis, "crypto", { value: webcrypto, configurable: true });
}
