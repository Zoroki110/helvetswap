import { zoro, SignRequestResponseType } from "../vendor/zoro-sdk";
import { IWalletConnector } from "./IWalletConnector";

type ZoroWallet = {
  partyId?: string;
  publicKey?: string;
  authToken?: string;
  getPartyId?: () => string | Promise<string>;
  getPublicKey?: () => string | Promise<string>;
  getHoldingUtxos?: () => Promise<any>;
  signMessage?: (message: string, cb: (resp: any) => void) => void;
};

const DEBUG = typeof window !== "undefined" && localStorage.getItem("clearportx.debug.zoro") === "1";
const CONNECT_TIMEOUT_MS = 45000;

let initDone = false;
let activeConnector: ZoroWalletConnector | null = null;

export class ZoroWalletConnector implements IWalletConnector {
  private wallet: ZoroWallet | null = null;
  private connectPromise: Promise<void> | null = null;
  private pendingResolve: (() => void) | null = null;
  private pendingReject: ((e: any) => void) | null = null;
  private pendingTimer: ReturnType<typeof setTimeout> | null = null;

  static ensureInitOnce(): void {
    if (initDone) return;
    const network = process.env.REACT_APP_ZORO_NETWORK || "mainnet";
    const walletUrl = process.env.REACT_APP_ZORO_WALLET_URL || undefined;
    const apiUrl = process.env.REACT_APP_ZORO_API_URL || undefined;
    const iconUrl =
      process.env.REACT_APP_ZORO_ICON_URL || (typeof window !== "undefined" ? window.location.origin : undefined);
    const appName = process.env.REACT_APP_ZORO_APP_NAME || "HelvetSwap";

    if (DEBUG) console.debug("[Zoro] init start", { network, walletUrl, apiUrl, iconUrl, appName });

    zoro.init({
      appName,
      iconUrl,
      network: network as "mainnet" | "local",
      walletUrl,
      apiUrl,
      onAccept: (wallet: ZoroWallet) => {
        activeConnector?.handleAccept(wallet);
      },
      onReject: () => {
        activeConnector?.handleReject(new Error("Zoro connection rejected"));
      },
      onDisconnect: () => {
        activeConnector?.handleDisconnect();
      },
    });

    if (DEBUG) console.debug("[Zoro] init done");
    initDone = true;
  }

  connectFromClick(): Promise<void> {
    activeConnector = this;
    ZoroWalletConnector.ensureInitOnce();
    if (DEBUG) console.debug("[Zoro] connect called");
    this.ensureConnectPromise();
    return this.connectPromise as Promise<void>;
  }

  async connect(): Promise<void> {
    activeConnector = this;
    ZoroWalletConnector.ensureInitOnce();
    if (this.wallet) return;
    if (this.connectPromise) {
      await this.connectPromise;
      return;
    }
    this.ensureConnectPromise();
    await this.connectPromise;
  }

  async getParty(): Promise<string> {
    await this.ensureConnected();
    const party =
      this.wallet?.partyId ??
      (typeof this.wallet?.getPartyId === "function" ? await this.wallet.getPartyId() : null);
    if (!party) {
      throw new Error("Zoro connect succeeded but partyId is missing. Allow popups and retry.");
    }
    return party;
  }

  getType(): "loop" | "zoro" | "dev" | "unknown" {
    return "zoro";
  }

  async getPublicKey(): Promise<string | null> {
    await this.ensureConnected();
    if (!this.wallet) return null;
    if (typeof this.wallet.getPublicKey === "function") {
      try {
        const key = await this.wallet.getPublicKey();
        if (typeof key === "string" && key.length > 0) return key;
      } catch {
        // fall through to field lookup
      }
    }
    return this.wallet.publicKey ?? null;
  }

  getProvider(): ZoroWallet | null {
    return this.wallet;
  }

  async signMessage(message: string): Promise<string> {
    await this.ensureConnected();
    if (!this.wallet || typeof this.wallet.signMessage !== "function") {
      throw new Error("Zoro wallet does not expose signMessage()");
    }

    return new Promise<string>((resolve, reject) => {
      try {
        this.wallet?.signMessage?.(message, (response: any) => {
          const type = response?.type;
          if (type === SignRequestResponseType.SIGN_REQUEST_APPROVED) {
            const sig = response?.data?.signature;
            if (typeof sig === "string") {
              if (DEBUG) console.debug("[Zoro] signMessage approved");
              resolve(sig);
              return;
            }
            reject(new Error("Zoro signature missing or not a string"));
            return;
          }
          if (type === SignRequestResponseType.SIGN_REQUEST_REJECTED) {
            if (DEBUG) console.debug("[Zoro] signMessage rejected");
            reject(new Error(response?.data?.reason || "Signature rejected"));
            return;
          }
          if (type === SignRequestResponseType.SIGN_REQUEST_ERROR) {
            if (DEBUG) console.debug("[Zoro] signMessage error");
            reject(new Error(response?.data?.error || "Signature error"));
            return;
          }
          reject(new Error("Unknown Zoro signMessage response"));
        });
      } catch (err) {
        reject(err);
      }
    });
  }

  async getHoldings(): Promise<any[]> {
    await this.ensureConnected();
    const getter = this.wallet?.getHoldingUtxos;
    if (typeof getter !== "function") {
      throw new Error("Zoro wallet does not expose getHoldingUtxos()");
    }
    const res = await getter.call(this.wallet);
    if (DEBUG) console.debug("[Zoro] getHoldingUtxos len=", Array.isArray(res) ? res.length : "n/a");
    return res;
  }

  async disconnect(): Promise<void> {
    await this.cleanup();
  }

  private ensureConnectPromise() {
    if (this.wallet || this.connectPromise) return;
    this.connectPromise = new Promise<void>((resolve, reject) => {
      this.pendingResolve = resolve;
      this.pendingReject = reject;
      this.pendingTimer = setTimeout(() => {
        if (DEBUG) console.debug("[Zoro] connect timeout");
        this.clearPending();
        void this.cleanup();
        reject(new Error("Zoro connect timeout. Allow popups for this site."));
      }, CONNECT_TIMEOUT_MS);
      try {
        zoro.connect();
      } catch (err) {
        this.clearPending();
        void this.cleanup();
        reject(err);
      }
    });
  }

  private async ensureConnected() {
    if (this.wallet) return;
    await this.connect();
    if (!this.wallet) {
      throw new Error("Zoro wallet did not provide a provider after connect()");
    }
  }

  private async handleAccept(wallet: ZoroWallet) {
    this.wallet = wallet;
    if (DEBUG) console.debug("[Zoro] onAccept wallet keys:", Object.keys(wallet || {}));
    if (this.pendingResolve) this.pendingResolve();
    this.clearPending();
  }

  private async handleReject(err: any) {
    if (DEBUG) console.debug("[Zoro] onReject");
    if (this.pendingReject) {
      this.pendingReject(err);
    }
    await this.cleanup();
    this.clearPending();
  }

  private async handleDisconnect() {
    if (DEBUG) console.debug("[Zoro] onDisconnect");
    await this.cleanup();
    this.clearPending();
  }

  private async cleanup() {
    this.wallet = null;
    this.clearPending();
    try {
      if (typeof window !== "undefined") {
        window.localStorage.removeItem("zoro_connect");
      }
    } catch {
      // ignore storage errors
    }
    try {
      await zoro.disconnect();
    } catch {
      // ignore
    }
  }

  private clearPending() {
    if (this.pendingTimer) {
      clearTimeout(this.pendingTimer);
    }
    this.pendingTimer = null;
    this.pendingResolve = null;
    this.pendingReject = null;
    this.connectPromise = null;
  }
}
