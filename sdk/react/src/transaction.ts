/**
 * The login transaction (state → verifier, nonce, returnTo) must survive the full-page redirect
 * to the IdP and back, so it cannot live in memory. It goes to `sessionStorage` (per tab, gone
 * when the tab closes), is single use, and expires after 10 minutes. TOKENS are never stored
 * here or anywhere else but memory.
 */
import type { LoginTransaction, TransactionStore } from "./types";

const PREFIX = "gorsso.tx.";
export const TRANSACTION_TTL_MS = 10 * 60 * 1000;

export class SessionStorageTransactionStore implements TransactionStore {
  constructor(private readonly storage: Storage = globalThis.sessionStorage) {}

  save(state: string, tx: LoginTransaction): void {
    this.storage.setItem(PREFIX + state, JSON.stringify(tx));
  }

  take(state: string): LoginTransaction | null {
    const key = PREFIX + state;
    const raw = this.storage.getItem(key);
    this.storage.removeItem(key);
    if (!raw) return null;
    try {
      const tx = JSON.parse(raw) as LoginTransaction;
      if (Date.now() - tx.createdAt > TRANSACTION_TTL_MS) return null;
      return tx;
    } catch {
      return null;
    }
  }
}

export class MemoryTransactionStore implements TransactionStore {
  private readonly map = new Map<string, LoginTransaction>();
  save(state: string, tx: LoginTransaction): void {
    this.map.set(state, tx);
  }
  take(state: string): LoginTransaction | null {
    const tx = this.map.get(state) ?? null;
    this.map.delete(state);
    if (tx && Date.now() - tx.createdAt > TRANSACTION_TTL_MS) return null;
    return tx;
  }
}

export function defaultTransactionStore(): TransactionStore {
  try {
    if (typeof globalThis.sessionStorage !== "undefined") return new SessionStorageTransactionStore();
  } catch {
    /* storage disabled */
  }
  return new MemoryTransactionStore();
}
