export type InstrumentSpec = {
  instrument_admin?: string;
  instrument_id: string;
};

export type TransferOptions = {
  requestedAt?: string | Date;
  executeBefore?: string | Date;
  requestTimeout?: number;
  memo?: string;
  message?: string;
  executionMode?: 'async' | 'wait';
  estimateTraffic?: boolean;
};

export type LoopProvider = {
  submitTransaction?: (command: any, opts?: any) => Promise<any>;
  executeAndWait?: (command: any, opts?: any) => Promise<any>;
  executeAndWaitForTransaction?: (command: any, opts?: any) => Promise<any>;
  transfer?: (
    recipient: string,
    amount: string | number,
    instrument?: InstrumentSpec,
    options?: TransferOptions
  ) => Promise<any>;
  // Access to underlying connection for legacy prepareTransfer
  connection?: any;
  auth_token?: string;
  authToken?: string;
  sdk?: any;
};

let activeProvider: LoopProvider | null = null;

export function setLoopProvider(provider: LoopProvider | null) {
  activeProvider = provider;
}

export function getLoopProvider(): LoopProvider | null {
  return activeProvider;
}
