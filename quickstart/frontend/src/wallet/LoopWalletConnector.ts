import * as loopModule from "@fivenorth/loop-sdk";
import { IWalletConnector } from "./IWalletConnector";
import { setLoopProvider } from "../loop/loopProvider";
import { submitTx } from "../loop/submitTx";

type LoopProvider = {
  party_id?: string;
  partyId?: string;
  party?: string;
  public_key?: string;
  publicKey?: string;
  signMessage?: (message: string) => Promise<any>;
  getHolding?: () => Promise<any>;
  getAccount?: () => Promise<any>;
  getActiveContracts?: (args: any) => Promise<any>;
  submitTransaction?: (cmd: any, opts?: any) => Promise<any>;
};

type LoopApi = {
  init?: (args: {
    appName: string;
    network: string;
    options?: { openMode?: "popup" | "tab"; redirectUrl?: string; requestSigningMode?: "popup" | "tab" };
    onAccept?: (provider: LoopProvider) => void;
    onReject?: () => void;
  }) => void;
  connect?: () => void;
  disconnect?: () => Promise<void> | void;
};

const DEBUG = typeof window !== "undefined" && localStorage.getItem("clearportx.debug.loop") === "1";
const POPUP_HINT = "Allow popups for this site.";
const CONNECT_TIMEOUT_MS = 120000;

const sdkModule: any = loopModule as any;
const resolvedLoopApi: LoopApi = sdkModule.loop ?? sdkModule.Loop ?? sdkModule.default ?? sdkModule;

let loopApi: LoopApi | null = resolvedLoopApi;
let initDone = false;
let activeConnector: LoopWalletConnector | null = null;

export class LoopWalletConnector implements IWalletConnector {
  private provider: LoopProvider | null = null;
  private connectPromise: Promise<void> | null = null;
  private pendingResolve: (() => void) | null = null;
  private pendingReject: ((e: any) => void) | null = null;
  private pendingTimer: ReturnType<typeof setTimeout> | null = null;
  private lastSignPublicKey: string | null = null;

  static initOnce(): void {
    if (initDone) return;
    if (DEBUG) console.debug("[Loop] init called");
    if (!loopApi || typeof loopApi.init !== "function" || typeof loopApi.connect !== "function") {
      throw new Error("Loop SDK is unavailable (missing init/connect)");
    }
    loopApi.init({
      appName: "HelvetSwap",
      network: process.env.REACT_APP_NETWORK || "mainnet",
      options: {
        openMode: "popup",
        requestSigningMode: "popup",
        redirectUrl: typeof window !== "undefined" ? window.location.href : undefined,
      },
      onAccept: (provider: LoopProvider) => {
        if (DEBUG)
          console.debug(
            "[Loop] onAccept keys",
            Object.keys(provider || {}),
            "signMessage?",
            typeof provider?.signMessage,
            "getHolding?",
            typeof provider?.getHolding
          );
        activeConnector?.handleAccept(provider);
      },
      onReject: () => {
        if (DEBUG) console.debug("[Loop] onReject");
        activeConnector?.handleReject(new Error("Loop connection rejected"));
      },
    });
    initDone = true;
  }

  async connect(): Promise<void> {
    if (this.provider) return;
    if (this.connectPromise) {
      await this.connectPromise;
      return;
    }
    activeConnector = this;
    if (!initDone) {
      LoopWalletConnector.initOnce();
    }
    this.ensureConnectPromise(false);
    await this.connectPromise;
  }

  /**
   * Should be called directly from the click handler (no async/await before calling).
   */
  connectFromClick(): Promise<void> {
    activeConnector = this;
    if (!initDone) {
      LoopWalletConnector.initOnce();
    }
    if (DEBUG) console.debug("[Loop] connect called in click stack at", Date.now());
    this.ensureConnectPromise(true);
    return this.connectPromise as Promise<void>;
  }

  async getParty(): Promise<string> {
    await this.connect();
    if (DEBUG) console.debug("[Loop] provider ready (getParty)");
    const party = this.provider?.party_id ?? this.provider?.partyId ?? this.provider?.party;
    if (!party) {
      throw new Error("Loop connect succeeded but partyId is missing. Allow popups and retry.");
    }
    return party;
  }

  async getPartyId(): Promise<string> {
    return this.getParty();
  }

  async signMessage(message: string): Promise<string> {
    await this.ensureConnectedOrThrow();
    if (DEBUG) console.debug("[Loop] provider ready (signMessage)");
    const signer = this.provider?.signMessage;
    if (!signer) {
      throw new Error("Loop wallet does not expose signMessage()");
    }
    const raw = await signer.call(this.provider, message);
    // Capture publicKey from the response if the wallet returns a structured result.
    // Canton Loop returns { signature, publicKey } so the backend verify endpoint can
    // run Ed25519 check against the exact key that signed.
    this.lastSignPublicKey = this.extractPublicKey(raw);
    const normalized = this.normalizeSignature(raw);
    if (DEBUG)
      console.debug("[Loop] signMessage result type", typeof raw, "len", typeof normalized === "string" ? normalized.length : 0, "pk?", !!this.lastSignPublicKey);
    return normalized;
  }

  async getPublicKey(): Promise<string | null> {
    // Prefer key captured during the most recent signMessage (authoritative — that's the
    // key that actually signed). Fall back to the provider-level key if available.
    if (this.lastSignPublicKey) return this.lastSignPublicKey;
    const providerKey = this.provider?.publicKey ?? this.provider?.public_key ?? null;
    return providerKey ?? null;
  }

  private extractPublicKey(res: any): string | null {
    if (!res || typeof res === "string") return null;
    if (typeof res.publicKey === "string") return res.publicKey;
    if (typeof res.public_key === "string") return res.public_key;
    if (typeof res.pubkey === "string") return res.pubkey;
    if (typeof res.pub_key === "string") return res.pub_key;
    if (res.publicKey && typeof res.publicKey.hex === "string") return res.publicKey.hex;
    if (res.publicKey && typeof res.publicKey.value === "string") return res.publicKey.value;
    return null;
  }

  private normalizeSignature(res: any): string {
    if (typeof res === "string") return res;
    if (res && typeof res.signature === "string") return res.signature;
    if (res && typeof res.sig === "string") return res.sig;
    if (res && typeof res.signedMessage === "string") return res.signedMessage;
    if (res && typeof res.signed_message === "string") return res.signed_message;
    if (res && typeof res.signature?.hex === "string") return res.signature.hex;
    if (res && typeof res.signature?.value === "string") return res.signature.value;
    throw new Error("Unsupported Loop signMessage response: " + JSON.stringify(Object.keys(res || {})));
  }

  async getHoldings(): Promise<any> {
    await this.ensureConnectedOrThrow();
    if (DEBUG) console.debug("[Loop] provider ready (getHoldings)");
    const gh = this.provider?.getHolding;
    if (typeof gh !== "function") {
      throw new Error("Loop provider does not expose getHolding()");
    }
    return gh.call(this.provider);
  }

  async getAccount(): Promise<any> {
    await this.ensureConnectedOrThrow();
    if (DEBUG) console.debug("[Loop] provider ready (getAccount)");
    const ga = this.provider?.getAccount;
    if (typeof ga !== "function") {
      throw new Error("Loop provider does not expose getAccount()");
    }
    return ga.call(this.provider);
  }

  async getActiveContracts(args: any): Promise<any> {
    await this.ensureConnectedOrThrow();
    if (DEBUG) console.debug("[Loop] provider ready (getActiveContracts)");
    const gac = this.provider?.getActiveContracts;
    if (typeof gac !== "function") {
      throw new Error("Loop provider does not expose getActiveContracts()");
    }
    return gac.call(this.provider, args);
  }

  async submitTransaction(cmd: any, opts?: any): Promise<any> {
    await this.ensureConnectedOrThrow();
    if (DEBUG) console.debug("[Loop] provider ready (submitTransaction)");
    const commands = Array.isArray(cmd?.commands) ? cmd.commands : Array.isArray(cmd) ? cmd : [];
    const actAs = cmd?.actAs ?? this.provider?.party_id ?? this.provider?.partyId ?? this.provider?.party;
    const readAs = cmd?.readAs;
    const deduplicationKey = cmd?.deduplicationKey ?? cmd?.commandId;
    const result = await submitTx({
      commands,
      actAs,
      readAs,
      deduplicationKey,
      memo: opts?.memo,
      estimateTraffic: opts?.estimateTraffic,
      mode: opts?.mode,
    });
    if (typeof window !== "undefined" && (process.env.REACT_APP_ENV === "devnet" || window.location.hostname === "localhost")) {
      try {
        console.debug("[Loop][devnet] submitTx result", result);
      } catch {
        /* ignore */
      }
    }
    return result;
  }

  /**
   * Accept an incoming CBTC TransferInstruction via Loop SDK.
   *
   * Flow:
   * 1. User provides the TransferInstruction contractId (extracted from TransferOffer)
   * 2. Loop SDK submits TransferInstruction_Accept on the TI interface
   * 3. Loop backend automatically provides disclosedContracts via authenticated registry access
   * 4. Returns the update containing the new CBTC Holding owned by receiver
   *
   * @param params.transferInstructionCid - Contract ID of the TransferInstruction to accept
   * @param params.receiverParty - Party accepting the CBTC (typically ClearportX operator)
   * @returns Promise with updateId and created contracts info
   */
  async acceptIncomingCbtcOffer(params: {
    transferInstructionCid: string;
    receiverParty: string;
    packageId?: string | null;
  }): Promise<{
    updateId: string;
    createdContracts: Array<{ templateId: string; contractId: string; payload?: any }>;
    success: boolean;
    error?: string;
  }> {
    await this.ensureConnectedOrThrow();
    const requestId = `loop-accept-${Date.now()}`;
    if (DEBUG) console.debug("[Loop] acceptIncomingCbtcOffer called", { ...params, requestId });

    // Use TransferInstruction interface from Splice API token standard
    // This is the correct template for CBTC acceptance (NOT TransferOffer)
    const templateId = "#splice-api-token-transfer-instruction-v1:Splice.Api.Token.TransferInstructionV1:TransferInstruction";

    const command = {
      commands: [
        {
          ExerciseCommand: {
            templateId,
            contractId: params.transferInstructionCid,
            choice: "TransferInstruction_Accept",
            choiceArgument: {},
          },
        },
      ],
    };

    try {
      if (DEBUG) console.debug("[Loop] Submitting CBTC Accept command:", { command, requestId });

      const result = await submitTx({
        commands: command.commands,
        actAs: [params.receiverParty],
        readAs: [params.receiverParty],
        deduplicationKey: requestId,
        mode: "WAIT",
      });

      if (DEBUG) console.debug("[Loop] CBTC Accept result:", { requestId, result });

      if (!result.ok) {
        return {
          updateId: "",
          createdContracts: [],
          success: false,
          error: result.error.message,
        };
      }

      if (result.value.txStatus !== "SUCCEEDED") {
        return {
          updateId: result.value.ledgerUpdateId || "",
          createdContracts: [],
          success: false,
          error: result.value.failures ? JSON.stringify(result.value.failures) : "Transaction failed",
        };
      }

      return {
        updateId: result.value.ledgerUpdateId || "",
        createdContracts: [],
        success: true,
      };
    } catch (err: any) {
      const errorMessage = err?.message || String(err);
      console.error("[Loop] CBTC Accept failed:", errorMessage);
      return {
        updateId: "",
        createdContracts: [],
        success: false,
        error: errorMessage,
      };
    }
  }

  async disconnect(): Promise<void> {
    await this.cleanupAndDisconnect();
    this.clearPending();
  }

  async cleanupAfterFailure(): Promise<void> {
    await this.cleanupAndDisconnect(true);
    this.clearPending();
  }

  getProvider(): LoopProvider | null {
    return this.provider;
  }

  getType(): "loop" | "zoro" | "dev" | "unknown" {
    return "loop";
  }

  /**
   * Non-intrusive connectivity check (no prompts).
   * Returns current connection status without triggering UI.
   */
  async checkConnected(): Promise<{ connected: boolean; error?: string }> {
    if (this.provider) {
      return { connected: true };
    }
    try {
      const rehydrated = await this.tryRehydrateFromStorage();
      if (rehydrated && this.provider) {
        return { connected: true };
      }
    } catch {
      /* ignore */
    }
    return { connected: false, error: "Not connected." };
  }

  /**
   * Ensure Loop session is usable. Tries reconnect once if needed.
   */
  async ensureConnected(requestId?: string): Promise<{ connected: boolean; error?: string }> {
    const status = await this.checkConnected();
    if (status.connected) return status;
    if (DEBUG) console.debug("[Loop] ensureConnected attempt connect", { requestId });
    try {
      await this.connect();
    } catch (err: any) {
      const msg = err?.message || "Not connected.";
      if (DEBUG) console.debug("[Loop] ensureConnected connect failed", msg, { requestId });
      return { connected: false, error: msg };
    }
    const after = await this.checkConnected();
    if (!after.connected && DEBUG) {
      console.debug("[Loop] ensureConnected still not connected", { requestId });
    }
    return after.connected ? after : { connected: false, error: "Not connected." };
  }

  private async ensureConnectedOrThrow() {
    if (this.provider) return;
    await this.connect();
    if (!this.provider) {
      console.warn("Loop provider missing after connect()");
      throw new Error("Loop wallet did not provide a provider after connect()");
    }
  }

  private async handleAccept(provider: LoopProvider) {
    this.provider = provider;
    setLoopProvider(provider);
    const partyId = provider.party_id ?? provider.partyId ?? provider.party;
    if (DEBUG) console.debug("[Loop] approved provider keys", Object.keys(provider || {}), "partyId", partyId);
    const checker = provider.getAccount ?? provider.getHolding;
    if (typeof checker === "function") {
      try {
        await checker.call(provider);
        if (DEBUG) console.debug("[Loop] sanity check passed");
      } catch (err) {
        if (DEBUG) console.debug("[Loop] sanity check failed", err);
        this.provider = null;
        this.handleReject(err);
        return;
      }
    }
    if (this.pendingResolve) this.pendingResolve();
    this.clearPending();
  }

  private handleReject(err: any) {
    if (this.pendingReject) {
      this.pendingReject(err);
    }
    void this.cleanupAndDisconnect(true);
    this.clearPending();
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

  hasProvider(): boolean {
    return !!this.provider;
  }

  /**
   * Attempt to resume a session if the SDK has already stored loop_connect.
   * This should be called when the user returns focus from the Loop tab or when storage changes.
   */
  async tryRehydrateFromStorage(): Promise<boolean> {
    if (this.provider) return true;
    if (this.connectPromise) return true;
    if (typeof window === "undefined") return false;
    const stored = window.localStorage.getItem("loop_connect");
    if (!stored) return false;
    let parsed: any = null;
    try {
      parsed = JSON.parse(stored);
    } catch {
      return false;
    }
    const hasSession = !!(parsed && (parsed.partyId || parsed.party_id || parsed.party) && parsed.authToken);
    if (!hasSession) return false;
    if (DEBUG) console.debug("[Loop] tryRehydrateFromStorage found session, calling connect()");
    try {
      this.ensureConnectPromise(false);
      return true;
    } catch (err) {
      if (DEBUG) console.debug("[Loop] rehydrate failed", err);
      return false;
    }
  }

  private ensureConnectPromise(fromClick: boolean) {
    if (this.provider) return;
    if (this.connectPromise) return;
    const startTs = Date.now();
    this.connectPromise = new Promise<void>((resolve, reject) => {
      this.pendingResolve = resolve;
      this.pendingReject = reject;
      this.pendingTimer = setTimeout(() => {
        if (DEBUG) console.debug("[Loop] connect timeout");
        this.clearPending();
        void this.cleanupAndDisconnect(true);
        reject(new Error(`Loop connect timeout. ${POPUP_HINT}`));
      }, CONNECT_TIMEOUT_MS);
      try {
        if (DEBUG) console.debug("[Loop] calling loop.connect NOW", startTs, "fromClick", fromClick);
        loopApi?.connect?.();
      } catch (err) {
        this.clearPending();
        void this.cleanupAndDisconnect(true);
        reject(err);
      }
    });
  }

  private async cleanupAndDisconnect(clearSession = false) {
    this.provider = null;
    setLoopProvider(null);
    this.clearLoopStorage(clearSession);
    try {
      await loopApi?.disconnect?.();
    } catch {
      // ignore
    }
  }

  private clearLoopStorage(clearSession: boolean) {
    try {
      if (typeof window !== "undefined") {
        window.localStorage.removeItem("loop_connect");
        if (clearSession) {
          window.localStorage.removeItem("clearportx.wallet.session");
        }
      }
    } catch {
      // ignore storage errors
    }
  }
}

// Netlify redeploy marker: do not remove. This forces a fresh build.
