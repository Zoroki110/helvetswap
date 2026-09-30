import { IWalletConnector } from "./IWalletConnector";

const STORAGE_KEY = "clearportx.devWallet.partyId";

export class DevWalletConnector implements IWalletConnector {
  private cachedPartyId: string | null;

  constructor() {
    this.cachedPartyId = this.readStoredParty();
  }

  async getParty(): Promise<string> {
    if (this.cachedPartyId) {
      return this.cachedPartyId;
    }
    if (typeof window === "undefined") {
      throw new Error("Dev wallet is only available in the browser.");
    }
    const input = window.prompt("Enter Canton party ID");
    const normalized = input?.trim();
    if (!normalized) {
      throw new Error("Party ID is required to continue.");
    }
    this.cachedPartyId = normalized;
    this.persistParty(normalized);
    return normalized;
  }

  async signMessage(_message: string): Promise<string> {
    return "dev-signature";
  }

  getType(): "loop" | "zoro" | "dev" | "unknown" {
    return "dev";
  }

  private readStoredParty(): string | null {
    if (typeof window === "undefined" || !window.localStorage) {
      return null;
    }
    try {
      const stored = window.localStorage.getItem(STORAGE_KEY);
      return stored && stored.trim().length > 0 ? stored : null;
    } catch {
      return null;
    }
  }

  private persistParty(partyId: string): void {
    if (typeof window === "undefined" || !window.localStorage) {
      return;
    }
    try {
      window.localStorage.setItem(STORAGE_KEY, partyId);
    } catch {
      // Ignore storage failures (e.g., private mode)
    }
  }
}
