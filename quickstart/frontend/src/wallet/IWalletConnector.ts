export interface IWalletConnector {
  /** Initialize/rehydrate the connector (optional for some wallets). */
  connect?(): Promise<void>;

  /** Resolve the active Canton party identifier exposed by the wallet. */
  getParty(): Promise<string>;

  /** Ask the wallet to sign an arbitrary message (UTF-8) for backend proof-of-possession. */
  signMessage(message: string): Promise<string>;

  /** Identify the connector type so the backend can capture wallet metadata. */
  getType(): "loop" | "zoro" | "dev" | "unknown";

  /** Expose the underlying provider when available (used by Loop for holdings/UTXOs). */
  getProvider?(): unknown;

  /** Ed25519 public key (hex/base64) of the signing key. Required by backend verify endpoint. */
  getPublicKey?(): Promise<string | null>;
}
