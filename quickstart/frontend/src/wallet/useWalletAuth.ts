import React from "react";
import { useCallback, useEffect, useState } from "react";
import { walletManager } from "./WalletManager";
import type { IWalletConnector } from "./IWalletConnector";
import { apiPostJson, clearWalletSession, loadWalletSession, persistWalletSession, setAuthToken } from "../api/client";
import toast from "react-hot-toast";

 type ChallengeResponse = {
   challengeId: string;
   challenge: string;
   expiresAt: string;
 };
 
 type VerifyResponse = {
   token: string;
   partyId: string;
 };
 
 export interface WalletAuthState {
   token: string | null;
   partyId: string | null;
   walletType: "loop" | "zoro" | "dev" | "unknown" | null;
   loading: boolean;
   error: string | null;
   authenticateWithLoop: () => Promise<VerifyResponse>;
   authenticateWithDev: () => Promise<VerifyResponse>;
   authenticateWithZoro: () => Promise<VerifyResponse>;
   disconnect: () => void;
 }
 
 type AuthInternalState = {
   token: string | null;
   partyId: string | null;
   walletType: "loop" | "zoro" | "dev" | "unknown" | null;
   loading: boolean;
   error: string | null;
 };
 
 const initialAuthState: AuthInternalState = {
   token: null,
   partyId: null,
   walletType: null,
   loading: false,
   error: null,
 };
 
 type AuthListener = (state: AuthInternalState) => void;
 
 let authState: AuthInternalState = initialAuthState;
 const authListeners = new Set<AuthListener>();
 let authHydratedFromSession = false;
 
 export function useWalletAuth(): WalletAuthState {
   hydrateAuthStateFromSession();
 
   const [state, setState] = useState<AuthInternalState>(authState);
 
   useEffect(() => {
     const listener: AuthListener = (next) => setState(next);
     authListeners.add(listener);
     return () => {
       authListeners.delete(listener);
     };
   }, []);
 
  useEffect(() => {
    try {
      walletManager.initLoopSdk();
    walletManager.initZoroSdk();
    } catch (err) {
      console.warn("Wallet init failed", err);
    }
  }, []);
 
   const runAuthFlow = useCallback(async (connect: () => Promise<IWalletConnector>) => {
     updateAuthState({ loading: true, error: null });
 
     let connector: IWalletConnector | null = null;
 
     try {
       connector = await connect();
       if (typeof connector.connect === "function") {
         await connector.connect();
       }
       const walletParty = await connector.getParty();
       const challenge = await requestChallenge(walletParty);
       const signature = await connector.signMessage(challenge.challenge);
 
       const verification = await verifyChallenge(connector, {
         challengeId: challenge.challengeId,
         partyId: walletParty,
         signature,
       });
 
       const connectorType = normalizeWalletType(connector.getType());
       setAuthToken(verification.token);
       persistWalletSession({
         token: verification.token,
         partyId: verification.partyId,
         walletType: connectorType,
       });
       updateAuthState({
         token: verification.token,
         partyId: verification.partyId,
         walletType: connectorType,
       });
 
       if (typeof window !== "undefined") {
         window.dispatchEvent(
           new CustomEvent("clearportx:wallet:connected", {
             detail: { partyId: verification.partyId, walletType: connectorType },
           })
         );
       }
 
       return verification;
     } catch (err) {
       const message = err instanceof Error ? err.message : "Wallet authentication failed";
       const lower = (message || "").toLowerCase();
       const hint =
         lower.includes("popup") || lower.includes("timeout") || lower.includes("block")
           ? " Allow popups for this site."
           : "";
       const finalMessage = `${message}${hint}`;
       updateAuthState({ error: finalMessage });
       toast.error(finalMessage);
       // Cleanup loop session on failure
       try {
         if (connector?.getType && connector.getType() === "loop") {
           await (connector as any).disconnect?.();
         }
       } catch {
         // ignore cleanup errors
       }
       throw err instanceof Error ? new Error(finalMessage) : err;
     } finally {
       updateAuthState({ loading: false });
     }
   }, []);
 
  const authenticateWithLoop = useCallback(async () => {
    updateAuthState({ loading: true, error: null });
    const connector = walletManager.getOrCreateLoopConnector();
    const debug = typeof window !== "undefined" && localStorage.getItem("clearportx.debug.loop") === "1";
    const log = (...args: any[]) => {
      if (debug) console.debug("[Loop]", ...args);
    };

    let pollingTimer: number | null = null;
    const pendingKey = "clearportx.pending.loop.auth";

    const setPending = (value: boolean) => {
      if (typeof window === "undefined") return;
      try {
        if (value) {
          window.sessionStorage.setItem(pendingKey, "1");
        } else {
          window.sessionStorage.removeItem(pendingKey);
        }
      } catch {
        /* ignore */
      }
    };

    const maybeRehydrate = () => {
      try {
        if (!(connector as any).hasProvider?.()) {
          void (connector as any).tryRehydrateFromStorage?.();
        }
      } catch {
        // ignore
      }
    };

    let cleanup: (() => void) | undefined;
    if (typeof window !== "undefined") {
      const onFocus = () => maybeRehydrate();
      const onVisibility = () => {
        if (document.visibilityState === "visible") maybeRehydrate();
      };
      const onStorage = (e: StorageEvent) => {
        if (e.key === "loop_connect") maybeRehydrate();
      };
      window.addEventListener("focus", onFocus);
      document.addEventListener("visibilitychange", onVisibility);
      window.addEventListener("storage", onStorage);
      cleanup = () => {
        window.removeEventListener("focus", onFocus);
        document.removeEventListener("visibilitychange", onVisibility);
        window.removeEventListener("storage", onStorage);
        if (pollingTimer !== null) clearInterval(pollingTimer);
        setPending(false);
      };

      // Poll up to 180s to resume without second click.
      pollingTimer = window.setInterval(() => {
        maybeRehydrate();
      }, 500) as unknown as number;
      setTimeout(() => {
        if (pollingTimer !== null) {
          clearInterval(pollingTimer);
          pollingTimer = null;
        }
      }, 180000);
    }

    try {
      log("connect started");
      setPending(true);
      connector.connectFromClick();
      maybeRehydrate();
      const walletParty = await connector.getParty();
      log("approved provider partyId", walletParty);
      log("auth: challenge requested");
      const challenge = await requestChallenge(walletParty);

      const signAndVerify = async (): Promise<VerifyResponse> => {
        log("auth: calling provider.signMessage");
        const rawSignature = await connector.signMessage(challenge.challenge);
        log("auth: signMessage resolved");
        const signature = normalizeLoopSignature(rawSignature);
        log("auth: verify called", {
          challengeId: challenge.challengeId,
          partyId: walletParty,
          signatureType: typeof signature,
        });
        return verifyChallenge(connector, {
          challengeId: challenge.challengeId,
          partyId: walletParty,
          signature,
        });
      };

      const signAndVerifyWithTimeout = async (): Promise<VerifyResponse> => {
        return Promise.race([
          signAndVerify(),
          new Promise<VerifyResponse>((_, reject) =>
            setTimeout(() => reject(new Error("Signature timed out after 120s")), 120000)
          ),
        ]);
      };

      const attemptWithRetry = async (): Promise<VerifyResponse> => {
        try {
          return await signAndVerifyWithTimeout();
        } catch (err) {
          const message = err instanceof Error ? err.message : "Signature failed";
          const retryable = /timeout|sign|connection|lost/i.test(message);
          if (!retryable) {
            throw err;
          }

          return await new Promise<VerifyResponse>((resolve, reject) => {
            const retry = async () => {
              try {
                const v = await signAndVerifyWithTimeout();
                resolve(v);
              } catch (e) {
                reject(e);
              }
            };

            toast.custom(
              (t) =>
                React.createElement(
                  "div",
                  { className: "bg-white dark:bg-dark-800 border border-gray-200 dark:border-gray-700 rounded-lg p-3 shadow" },
                  [
                    React.createElement(
                      "div",
                      { key: "msg", className: "text-sm font-semibold text-gray-800 dark:text-gray-100" },
                      "Signature required — click to retry"
                    ),
                    React.createElement(
                      "div",
                      { key: "actions", className: "mt-2 flex gap-2" },
                      [
                        React.createElement(
                          "button",
                          {
                            key: "retry",
                            className: "px-3 py-1 rounded bg-emerald-600 text-white text-sm",
                            onClick: () => {
                              toast.dismiss(t.id);
                              void retry();
                            },
                          },
                          "Retry Signature"
                        ),
                        React.createElement(
                          "button",
                          {
                            key: "cancel",
                            className: "px-3 py-1 rounded border text-sm text-gray-600 dark:text-gray-300",
                            onClick: () => {
                              toast.dismiss(t.id);
                              reject(err);
                            },
                          },
                          "Cancel"
                        ),
                      ]
                    ),
                  ]
                ),
              { duration: 60000 }
            );
          });
        }
      };

      const verification = await attemptWithRetry();

      const connectorType = normalizeWalletType(connector.getType());
      setAuthToken(verification.token);
      persistWalletSession({
        token: verification.token,
        partyId: verification.partyId,
        walletType: connectorType,
      });
      updateAuthState({
        token: verification.token,
        partyId: verification.partyId,
        walletType: connectorType,
      });

      if (typeof window !== "undefined") {
        window.dispatchEvent(
          new CustomEvent("clearportx:wallet:connected", {
            detail: { partyId: verification.partyId, walletType: connectorType },
          })
        );
      }

      log("auth success");
      return verification;
    } catch (err) {
      const message = err instanceof Error ? err.message : "Wallet authentication failed";
      updateAuthState({ error: message });
      toast.error(message);
      // Keep Loop session intact for manual retry; do not eagerly remove loop_connect here.
      throw err instanceof Error ? err : new Error(message);
    } finally {
      updateAuthState({ loading: false });
      if (cleanup) cleanup();
    }
  }, []);
   const authenticateWithDev = useCallback(
     () => runAuthFlow(() => walletManager.connectDev()),
     [runAuthFlow]
   );
 
   const authenticateWithZoro = useCallback(
    async () => {
      updateAuthState({ loading: true, error: null });
      const connector = walletManager.getOrCreateZoroConnector();
      const debug = typeof window !== "undefined" && localStorage.getItem("clearportx.debug.zoro") === "1";
      const log = (...args: any[]) => {
        if (debug) console.debug("[Zoro]", ...args);
      };

      try {
        connector.connectFromClick();
        const walletParty = await connector.getParty();
        log("auth: challenge requested");
        const challenge = await requestChallenge(walletParty);
        log("auth: calling wallet.signMessage");
        const signature = await connector.signMessage(challenge.challenge);
        log("auth: verify called", {
          challengeId: challenge.challengeId,
          partyId: walletParty,
          signatureType: typeof signature,
        });

        const verification = await verifyChallenge(connector, {
          challengeId: challenge.challengeId,
          partyId: walletParty,
          signature,
        });

        const connectorType = normalizeWalletType(connector.getType());
        setAuthToken(verification.token);
        persistWalletSession({
          token: verification.token,
          partyId: verification.partyId,
          walletType: connectorType,
        });
        updateAuthState({
          token: verification.token,
          partyId: verification.partyId,
          walletType: connectorType,
        });

        if (typeof window !== "undefined") {
          window.dispatchEvent(
            new CustomEvent("clearportx:wallet:connected", {
              detail: { partyId: verification.partyId, walletType: connectorType },
            })
          );
        }

        return verification;
      } catch (err) {
        const message = err instanceof Error ? err.message : "Wallet authentication failed";
        updateAuthState({ error: message });
        toast.error(message);
        try {
          await (connector as any).disconnect?.();
          if (typeof window !== "undefined") {
            window.localStorage.removeItem("zoro_connect");
          }
        } catch {
          // ignore cleanup errors
        }
        throw err instanceof Error ? err : new Error(message);
      } finally {
        updateAuthState({ loading: false });
      }
    },
    []
  );
 
   const disconnect = useCallback(() => {
     clearWalletSession();
     setAuthToken(null);
     try {
       walletManager.getLoopConnector()?.disconnect?.();
       if (typeof window !== "undefined") {
         window.localStorage.removeItem("loop_connect");
       }
      walletManager.getZoroConnector()?.disconnect?.();
      if (typeof window !== "undefined") {
        window.localStorage.removeItem("zoro_connect");
      }
     } catch {
       // ignore cleanup errors
     }
     updateAuthState({
       token: null,
       partyId: null,
       walletType: null,
       error: null,
     });
   }, []);
 
   return {
     token: state.token,
     partyId: state.partyId,
     walletType: state.walletType,
     loading: state.loading,
     error: state.error,
     authenticateWithLoop,
     authenticateWithDev,
     authenticateWithZoro,
     disconnect,
   };
 }
 
 async function requestChallenge(partyId: string): Promise<ChallengeResponse> {
   return apiPostJson<ChallengeResponse>("/api/auth/challenge", { partyId });
 }
 
 async function verifyChallenge(
   connector: IWalletConnector,
   params: { challengeId: string; partyId: string; signature: string }
 ): Promise<VerifyResponse> {
   const publicKey = typeof connector.getPublicKey === "function"
     ? await connector.getPublicKey()
     : null;
   if (!publicKey) {
     throw new Error(
       "Wallet did not expose a public key. The backend needs it to verify the Ed25519 signature. " +
       "Loop returns it in the signMessage response — check that the SDK version supports this."
     );
   }
   return apiPostJson<VerifyResponse>("/api/auth/verify", {
     ...params,
     publicKey,
     walletType: connector.getType(),
   });
 }
 
 function normalizeWalletType(value: string | null | undefined): "loop" | "zoro" | "dev" | "unknown" {
   if (value === "loop" || value === "zoro" || value === "dev" || value === "unknown") {
     return value;
   }
   return "unknown";
 }

 function normalizeLoopSignature(result: any): string {
   if (typeof result === "string") return result;
   if (result && typeof result.signature === "string") return result.signature;
   if (result && typeof result.sig === "string") return result.sig;
   if (result && typeof result.signedMessage === "string") return result.signedMessage;
   if (result && typeof result.signed_message === "string") return result.signed_message;
   throw new Error("Loop signature is not a string");
 }
 
 function hydrateAuthStateFromSession() {
   if (authHydratedFromSession) {
     return;
   }
   authHydratedFromSession = true;
   const session = loadWalletSession();
   if (session?.token && session.partyId) {
     setAuthToken(session.token);
     authState = {
       ...authState,
       token: session.token,
       partyId: session.partyId,
       walletType: normalizeWalletType(session.walletType),
     };
   }
 }
 
 function updateAuthState(patch: Partial<AuthInternalState>) {
   authState = { ...authState, ...patch };
   authListeners.forEach((listener) => listener(authState));
 }
