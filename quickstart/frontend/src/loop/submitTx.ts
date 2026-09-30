import { getLoopProvider } from "./loopProvider";

export type SubmitTxMode = "WAIT" | "LEGACY";

export type SubmitTxSuccess = {
  ledgerUpdateId: string;
  transactionId?: string;
  txStatus: "SUCCEEDED" | "FAILED";
  failures?: any;
  memoEcho?: string;
  trafficEstimation?: {
    estimationTimestamp: string;
    confirmationRequestTrafficCostEstimation: number;
    confirmationResponseTrafficCostEstimation: number;
    totalTrafficCostEstimation: number;
  };
};

export type SubmitTxError = {
  code: string;
  message: string;
  details?: any;
};

export type Result<T, E> =
  | { ok: true; value: T }
  | { ok: false; error: E };

export type SubmitTxInput = {
  commands: any[];
  actAs: string | string[];
  readAs?: string | string[];
  deduplicationKey?: string;
  memo?: string;
  estimateTraffic?: boolean;
  mode?: SubmitTxMode;
  disclosedContracts?: any[];
  packageIdSelectionPreference?: string[];
  synchronizerId?: string;
};

const DEFAULT_APP_ID = "clearportx";
const MEMO_KEY = "splice.lfdecentralizedtrust.org/reason";

function resolveMode(mode?: SubmitTxMode): SubmitTxMode {
  const forced = (process.env.REACT_APP_LOOP_SUBMIT_MODE ?? "").toUpperCase();
  if (forced === "WAIT" || forced === "LEGACY") {
    return forced as SubmitTxMode;
  }
  return mode ?? "WAIT";
}

function legacyEnabled(): boolean {
  return (process.env.REACT_APP_LOOP_SUBMIT_LEGACY ?? "true") !== "false";
}

function normalizePartyList(value: string | string[] | undefined): string[] | undefined {
  if (!value) return undefined;
  return Array.isArray(value) ? value : [value];
}

function applyMemoToArgument(arg: any, memo?: string): any {
  if (!memo || !arg || typeof arg !== "object") return arg;
  const next = { ...arg };
  if (next.meta && typeof next.meta === "object") {
    next.meta = {
      ...next.meta,
      values: {
        ...(next.meta.values ?? {}),
        [MEMO_KEY]: memo,
      },
    };
    return next;
  }
  if (next.transfer && typeof next.transfer === "object") {
    next.transfer = applyMemoToArgument(next.transfer, memo);
    return next;
  }
  return next;
}

function applyMemoToCommands(commands: any[], memo?: string): any[] {
  if (!memo) return commands;
  let cloned: any[] = commands;
  try {
    cloned = JSON.parse(JSON.stringify(commands));
  } catch {
    cloned = commands.map((cmd) => ({ ...cmd }));
  }
  return cloned.map((cmd) => {
    if (cmd?.ExerciseCommand) {
      const choiceArgument = applyMemoToArgument(cmd.ExerciseCommand.choiceArgument, memo);
      return {
        ...cmd,
        ExerciseCommand: {
          ...cmd.ExerciseCommand,
          choiceArgument,
        },
      };
    }
    if (cmd?.CreateCommand) {
      const createArguments = applyMemoToArgument(cmd.CreateCommand.createArguments, memo);
      return {
        ...cmd,
        CreateCommand: {
          ...cmd.CreateCommand,
          createArguments,
        },
      };
    }
    return cmd;
  });
}

function toTemplateIdString(templateId: any): any {
  if (typeof templateId === "string") return templateId;
  if (
    templateId &&
    typeof templateId === "object" &&
    typeof templateId.packageId === "string" &&
    typeof templateId.moduleName === "string" &&
    typeof templateId.entityName === "string"
  ) {
    return `${templateId.packageId}:${templateId.moduleName}:${templateId.entityName}`;
  }
  return templateId;
}

function normalizeTemplateIds(commands: any[]): any[] {
  return commands.map((cmd) => {
    if (cmd?.CreateCommand?.templateId) {
      const templateId = toTemplateIdString(cmd.CreateCommand.templateId);
      if (templateId !== cmd.CreateCommand.templateId) {
        return {
          ...cmd,
          CreateCommand: {
            ...cmd.CreateCommand,
            templateId,
          },
        };
      }
    }
    if (cmd?.ExerciseCommand?.templateId) {
      const templateId = toTemplateIdString(cmd.ExerciseCommand.templateId);
      if (templateId !== cmd.ExerciseCommand.templateId) {
        return {
          ...cmd,
          ExerciseCommand: {
            ...cmd.ExerciseCommand,
            templateId,
          },
        };
      }
    }
    if (cmd?.ExerciseByKeyCommand?.templateId) {
      const templateId = toTemplateIdString(cmd.ExerciseByKeyCommand.templateId);
      if (templateId !== cmd.ExerciseByKeyCommand.templateId) {
        return {
          ...cmd,
          ExerciseByKeyCommand: {
            ...cmd.ExerciseByKeyCommand,
            templateId,
          },
        };
      }
    }
    return cmd;
  });
}

function buildEnvelope(input: SubmitTxInput, commands: any[]) {
  const commandId = input.deduplicationKey || `loop-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`;
  const workflowId = input.deduplicationKey ? `wf-${input.deduplicationKey}` : `wf-${Date.now()}`;
  const actAs = normalizePartyList(input.actAs) ?? [];
  const readAs = normalizePartyList(input.readAs);
  const disclosedContracts = Array.isArray(input.disclosedContracts) ? input.disclosedContracts : [];
  const envelope: any = {
    commandId,
    workflowId,
    applicationId: DEFAULT_APP_ID,
    actAs,
    commands,
  };
  if (disclosedContracts.length > 0) {
    envelope.disclosedContracts = disclosedContracts;
  }
  if (Array.isArray(input.packageIdSelectionPreference) && input.packageIdSelectionPreference.length > 0) {
    envelope.packageIdSelectionPreference = input.packageIdSelectionPreference;
  }
  if (input.synchronizerId) {
    envelope.synchronizerId = input.synchronizerId;
  }
  if (readAs && readAs.length > 0) {
    envelope.readAs = readAs;
  }
  if (input.deduplicationKey) {
    envelope.deduplicationKey = input.deduplicationKey;
  }
  return envelope;
}

function extractTrafficEstimation(resp: any): SubmitTxSuccess["trafficEstimation"] | undefined {
  const traffic =
    resp?.trafficEstimation ??
    resp?.traffic_cost_estimation ??
    resp?.trafficCostEstimation ??
    resp?.traffic?.estimation ??
    resp?.traffic;
  if (!traffic) return undefined;
  return {
    estimationTimestamp: traffic.estimationTimestamp ?? traffic.timestamp ?? "",
    confirmationRequestTrafficCostEstimation:
      traffic.confirmationRequestTrafficCostEstimation ??
      traffic.confirmationRequestCost ??
      traffic.requestCost ??
      0,
    confirmationResponseTrafficCostEstimation:
      traffic.confirmationResponseTrafficCostEstimation ??
      traffic.confirmationResponseCost ??
      traffic.responseCost ??
      0,
    totalTrafficCostEstimation:
      traffic.totalTrafficCostEstimation ?? traffic.totalCost ?? traffic.total ?? 0,
  };
}

function extractFailures(resp: any): any {
  return resp?.failures ?? resp?.failure ?? resp?.errors ?? resp?.error ?? null;
}

function extractStatus(resp: any): "SUCCEEDED" | "FAILED" {
  const status = resp?.txStatus ?? resp?.status ?? resp?.result?.status ?? null;
  if (typeof status === "string") {
    const upper = status.toUpperCase();
    if (upper.includes("FAIL")) return "FAILED";
    if (upper.includes("SUCCESS")) return "SUCCEEDED";
  }
  const failures = extractFailures(resp);
  if (failures) return "FAILED";
  return "SUCCEEDED";
}

function extractLedgerUpdateId(resp: any): string {
  return (
    resp?.ledgerUpdateId ??
    resp?.updateId ??
    resp?.update_id ??
    resp?.updateID ??
    resp?.transactionId ??
    resp?.transaction?.updateId ??
    resp?.transaction?.update_id ??
    resp?.transaction?.transactionId ??
    resp?.transaction?.transaction_id ??
    resp?.update?.updateId ??
    resp?.update?.update_id ??
    resp?.updateData?.updateId ??
    resp?.updateData?.update_id ??
    resp?.update_data?.update_id ??
    resp?.update_data?.updateId ??
    resp?.result?.updateId ??
    resp?.result?.update_id ??
    resp?.result?.transactionId ??
    resp?.result?.transaction_id ??
    resp?.commandId ??
    resp?.command_id ??
    ""
  );
}

function extractTransactionId(resp: any): string | undefined {
  return resp?.transactionId ?? resp?.transaction?.transactionId ?? undefined;
}

function extractMemoEcho(resp: any, memo?: string): string | undefined {
  if (!resp) return memo;
  const queue: any[] = [resp];
  while (queue.length) {
    const current = queue.shift();
    if (current && typeof current === "object") {
      if (current.meta?.values && current.meta.values[MEMO_KEY]) {
        return current.meta.values[MEMO_KEY];
      }
      for (const value of Object.values(current)) {
        if (value && typeof value === "object") {
          queue.push(value);
        }
      }
    }
  }
  return memo;
}

function mapError(err: any): SubmitTxError {
  if (!err) {
    return { code: "UNKNOWN", message: "Unknown error" };
  }
  if (typeof err === "string") {
    return { code: "ERROR", message: err };
  }
  return {
    code: err.code || err.name || "ERROR",
    message: err.message || String(err),
    details: err,
  };
}

export async function submitTx(input: SubmitTxInput): Promise<Result<SubmitTxSuccess, SubmitTxError>> {
  const provider = getLoopProvider();
  if (!provider) {
    return {
      ok: false,
      error: { code: "NO_PROVIDER", message: "Loop provider is not connected" },
    };
  }
  if (!input.commands || !Array.isArray(input.commands) || input.commands.length === 0) {
    return {
      ok: false,
      error: { code: "VALIDATION", message: "commands is required" },
    };
  }
  if (!input.actAs || (Array.isArray(input.actAs) && input.actAs.length === 0)) {
    return {
      ok: false,
      error: { code: "VALIDATION", message: "actAs is required" },
    };
  }

  const mode = resolveMode(input.mode);
  const commandsWithMemo = applyMemoToCommands(input.commands, input.memo);
  const normalizedCommands = normalizeTemplateIds(commandsWithMemo);
  const envelope = buildEnvelope(input, normalizedCommands);
  const opts: any = {
    timeoutMs: 120000,
  };
  if (input.estimateTraffic) {
    opts.estimateTraffic = true;
  }

  const waitFn =
    (provider as any).submitAndWaitForTransaction ||
    (provider as any).executeAndWait ||
    (provider as any).executeAndWaitForTransaction;
  const legacyFn = (provider as any).submitTransaction;

  if (mode === "WAIT" && typeof waitFn !== "function" && !legacyEnabled()) {
    return {
      ok: false,
      error: { code: "NOT_SUPPORTED", message: "execute-and-wait is not supported by this Loop SDK" },
    };
  }
  if (mode === "LEGACY" && typeof legacyFn !== "function") {
    return {
      ok: false,
      error: { code: "NOT_SUPPORTED", message: "submitTransaction is not supported by this Loop SDK" },
    };
  }

  try {
    let response: any;
    if (mode === "WAIT" && typeof waitFn === "function") {
      response = await waitFn.call(provider, envelope, opts);
    } else {
      const legacyOpts = { ...opts };
      if (mode === "WAIT") {
        legacyOpts.mode = "execute-and-wait";
      }
      if (typeof legacyFn !== "function") {
        return {
          ok: false,
          error: { code: "NOT_SUPPORTED", message: "submitTransaction is not available" },
        };
      }
      response = await legacyFn.call(provider, envelope, legacyOpts);
    }

    const failures = extractFailures(response);
    const result: SubmitTxSuccess = {
      ledgerUpdateId: extractLedgerUpdateId(response),
      transactionId: extractTransactionId(response),
      txStatus: extractStatus(response),
      failures: failures || undefined,
      memoEcho: extractMemoEcho(response, input.memo),
      trafficEstimation: input.estimateTraffic ? extractTrafficEstimation(response) : undefined,
    };
    return { ok: true, value: result };
  } catch (err: any) {
    return { ok: false, error: mapError(err) };
  }
}

