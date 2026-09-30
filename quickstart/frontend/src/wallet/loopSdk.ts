// Lightweight singleton wrapper around the Loop SDK to guard init/connect flows.
// Keeps the provider in-memory only (no persistence) and resolves connect only after onAccept.
type LoopProvider = {
  party_id?: string;
  partyId?: string;
  party?: string;
  // Other fields may exist, but we intentionally avoid relying on provider.connection.
  authToken?: string;
  signMessage?: (message: string) => Promise<string>;
  getHolding?: () => Promise<Array<Record<string, unknown>>>;
  getActiveContracts?: (args: { interfaceId?: string; templateId?: string }) => Promise<any[]>;
};

type LoopApi = {
  init?: (args: {
    appName: string;
    network: string;
    options?: { openMode?: "popup" | "tab"; redirectUrl?: string; requestSigningMode?: "popup" | "tab" };
    onAccept?: (provider: LoopProvider) => void;
    onReject?: () => void;
  }) => void;
  connect?: () => Promise<unknown>;
  disconnect?: () => Promise<unknown>;
};

const DEBUG = false;
const POPUP_HINT = "Allow popups for this site.";

let initPromise: Promise<void> | null = null;
let loopApi: LoopApi | null = null;
let provider: LoopProvider | null = null;
let connectWaiters: Array<{ resolve: (p: LoopProvider | null) => void; reject: (err: any) => void }> = [];
let connectInFlight: Promise<LoopProvider> | null = null;

async function loadSdk(): Promise<LoopApi> {
  const sdkModule = await import("@fivenorth/loop-sdk");
  const api: LoopApi =
    (sdkModule as any).loop ??
    (sdkModule as any).Loop ??
    (sdkModule as any).default ??
    sdkModule;
  return api;
}

export function getProvider(): LoopProvider | null {
  return provider;
}

export async function initOnce(): Promise<void> {
  if (initPromise) return initPromise;

  initPromise = (async () => {
    loopApi = await loadSdk();
    if (!loopApi || typeof loopApi.init !== "function" || typeof loopApi.connect !== "function") {
      throw new Error("Loop SDK is unavailable (missing init/connect)");
    }

    const onAccept = (prov: LoopProvider) => {
      provider = prov;
      if (DEBUG) console.debug("[LoopSdk] onAccept provider keys", Object.keys(prov || {}));
      connectWaiters.forEach((w) => w.resolve(provider));
      connectWaiters = [];
    };

    const onReject = () => {
      provider = null;
      const err = new Error("Loop connection rejected by user");
      connectWaiters.forEach((w) => w.reject(err));
      connectWaiters = [];
      if (DEBUG) console.debug("[LoopSdk] onReject");
    };

    loopApi.init({
      appName: "HelvetSwap",
      network: process.env.REACT_APP_NETWORK || "mainnet",
      options: { openMode: "popup", requestSigningMode: "popup" },
      onAccept,
      onReject,
    });
    if (DEBUG) console.debug("[LoopSdk] init invoked");
  })();

  return initPromise;
}

export async function connectOnce(): Promise<LoopProvider> {
  await initOnce();

  const api = loopApi;
  const connectFn = api?.connect;
  if (!api || typeof connectFn !== "function") {
    throw new Error("Loop SDK connect() is not available");
  }
  if (provider) {
    return provider;
  }
  if (connectInFlight) {
    return connectInFlight;
  }

  const connect = connectFn;

  const attempt = (): Promise<LoopProvider> =>
    new Promise<LoopProvider>((resolve, reject) => {
      const timeout = setTimeout(() => {
        reject(new Error(`Loop connect timeout. ${POPUP_HINT}`));
      }, 60000);

      connectWaiters = [];

      const waiter = {
        resolve: (p: LoopProvider | null) => {
          clearTimeout(timeout);
          if (!p) {
            reject(new Error(`Loop provider not returned. ${POPUP_HINT}`));
          } else {
            resolve(p);
          }
        },
        reject: (err: any) => {
          clearTimeout(timeout);
          reject(err);
        },
      };

      connectWaiters.push(waiter);

      try {
        const res = connect();
        if (res && typeof (res as Promise<unknown>).catch === "function") {
          (res as Promise<unknown>).catch((err) => waiter.reject(err));
        }
      } catch (err) {
        waiter.reject(err);
      }
    });

  const run = async (): Promise<LoopProvider> => {
    try {
      return await attempt();
    } catch (err: any) {
      const msg = String(err?.message ?? err ?? "");
      const isConn = msg.toLowerCase().includes("connection");
      if (isConn) {
        if (typeof api.disconnect === "function") {
          try {
            await api.disconnect();
          } catch {
            // ignore disconnect errors
          }
        }
        try {
          return await attempt();
        } catch (err2: any) {
          const msg2 = String(err2?.message ?? err2 ?? "");
          const hint2 =
            msg2.toLowerCase().includes("popup") || msg2.toLowerCase().includes("block")
              ? ` ${POPUP_HINT}`
              : "";
          throw new Error(`${msg2}${hint2}`);
        }
      }
      const hint =
        msg.toLowerCase().includes("popup") || msg.toLowerCase().includes("block")
          ? ` ${POPUP_HINT}`
          : "";
      throw new Error(`${msg}${hint}`);
    } finally {
      connectInFlight = null;
    }
  };

  connectInFlight = run();
  return connectInFlight;
}

export type { LoopProvider, LoopApi };

