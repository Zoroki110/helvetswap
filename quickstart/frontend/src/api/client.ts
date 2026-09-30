import { BUILD_INFO } from "../config/build-info";

type HttpMethod = "GET" | "POST" | "PUT" | "DELETE";

const runtimeBackendUrl =
  typeof window !== "undefined" ? (window as any).__BACKEND_URL__ : undefined;

const BASE_URL =
  process.env.REACT_APP_BACKEND_API_URL ||
  BUILD_INFO?.features?.backendUrl ||
  runtimeBackendUrl ||
  "http://localhost:8080";

const DEFAULT_HEADERS: Record<string, string> = {
  "Content-Type": "application/json",
};

const WALLET_SESSION_KEY = "clearportx.wallet.session";

export type WalletSession = {
  token: string;
  partyId: string;
  walletType: string;
  expiresAt?: string | null;
};

let walletAuthToken: string | null = null;
let activeWalletSession: WalletSession | null = null;
let sessionHydrated = false;

export function setAuthToken(token: string | null) {
  walletAuthToken = token && token.trim().length > 0 ? token : null;
}

export function getAuthToken(): string | null {
  ensureSessionHydrated();
  return walletAuthToken;
}

export function persistWalletSession(session: WalletSession | null) {
  sessionHydrated = true;
  activeWalletSession = session;
  walletAuthToken = session?.token ?? null;
  if (typeof window === "undefined") {
    return;
  }
  if (!session) {
    window.localStorage.removeItem(WALLET_SESSION_KEY);
    return;
  }
  try {
    window.localStorage.setItem(WALLET_SESSION_KEY, JSON.stringify(session));
  } catch (err) {
    console.warn("Failed to persist wallet session", err);
  }
}

export function loadWalletSession(): WalletSession | null {
  ensureSessionHydrated();
  return activeWalletSession;
}

export function clearWalletSession() {
  sessionHydrated = true;
  activeWalletSession = null;
  walletAuthToken = null;
  persistWalletSession(null);
}

export function getActiveWalletParty(): string | null {
  ensureSessionHydrated();
  return activeWalletSession?.partyId ?? null;
}

export async function apiGet<T>(path: string): Promise<T> {
  return request<T>("GET", path);
}

export async function apiPostJson<T>(path: string, body: unknown): Promise<T> {
  return request<T>("POST", path, body);
}

async function request<T>(method: HttpMethod, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { ...DEFAULT_HEADERS };
  const token = getAuthToken();
  if (token) {
    headers["Authorization"] = `Bearer ${token}`;
  }

  const response = await fetch(`${BASE_URL}${path}`, {
    method,
    headers,
    credentials: "same-origin",
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });

  const payload = await parseJson(response);
  if (!response.ok) {
    console.warn("[API] Non-2xx response", response.url, response.status);
    const message =
      payload?.message || payload?.error || `Request to ${path} failed with status ${response.status}`;
    throw new Error(message);
  }
  if (payload === null) {
    console.warn("[API] Empty JSON response", response.url);
    throw new Error(`Request to ${path} returned an empty response`);
  }
  return payload as T;
}

type ErrorPayload = {
  message?: string;
  error?: string;
  [key: string]: unknown;
};

async function parseJson(response: Response): Promise<ErrorPayload | null> {
  const contentType = response.headers.get("content-type") || "";
  if (!contentType.includes("application/json")) {
    return null;
  }
  try {
    return (await response.json()) as ErrorPayload;
  } catch {
    return null;
  }
}

function ensureSessionHydrated() {
  if (sessionHydrated || typeof window === "undefined") {
    return;
  }
  sessionHydrated = true;
  const stored = readSessionFromStorage();
  if (stored) {
    activeWalletSession = stored;
    walletAuthToken = stored.token;
  }
}

function readSessionFromStorage(): WalletSession | null {
  const raw = window.localStorage.getItem(WALLET_SESSION_KEY);
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw) as WalletSession;
    if (parsed?.token && parsed?.partyId) {
      return parsed;
    }
  } catch (err) {
    console.warn("Failed to parse wallet session", err);
  }
  window.localStorage.removeItem(WALLET_SESSION_KEY);
  return null;
}
