import type { TokenResponse } from './types';

/**
 * The in-memory session (AD-8). The access token lives only here, never in storage, so a hard refresh
 * drops it and the HttpOnly refresh cookie (sent by the browser, never read by us) brings it back.
 */
export const REFRESH_URL = '/api/v1/auth/refresh';

/** Refresh this long before expiry, so requests rarely meet an expired token. */
export const REFRESH_LEAD_MS = 60_000;

/** `unavailable` is a network or server failure: not a logout. */
export type RefreshOutcome = 'refreshed' | 'signed-out' | 'unavailable';

type Session = { accessToken: string; expiresAt: number };

let session: Session | null = null;
let inFlight: Promise<RefreshOutcome> | null = null;
let timer: ReturnType<typeof setTimeout> | undefined;
// Bumped by endSession, so a refresh that started before a logout cannot bring the session back.
let generation = 0;
const endListeners = new Set<() => void>();

export function accessToken(): string | null {
  return session?.accessToken ?? null;
}

export function isAccessTokenExpired(): boolean {
  return session !== null && Date.now() >= session.expiresAt;
}

/** Counts the lifetime from now (when the response arrived), so the client clock's skew does not matter. */
export function startSession(token: TokenResponse): void {
  const lifetimeMs = token.expiresIn * 1000;
  session = { accessToken: token.accessToken, expiresAt: Date.now() + lifetimeMs };
  clearTimeout(timer);
  timer = setTimeout(() => {
    void refreshSession();
  }, Math.max(0, lifetimeMs - REFRESH_LEAD_MS));
}

export function endSession(): void {
  const hadSession = session !== null;
  generation += 1;
  session = null;
  inFlight = null;
  clearTimeout(timer);
  timer = undefined;
  if (hadSession) {
    endListeners.forEach((listener) => listener());
  }
}

/** Single-flight (AD-8): every caller during one refresh gets the same promise. */
export function refreshSession(): Promise<RefreshOutcome> {
  if (inFlight) {
    return inFlight;
  }
  const attempt: Promise<RefreshOutcome> = requestRefresh(generation).finally(() => {
    if (inFlight === attempt) {
      inFlight = null;
    }
  });
  inFlight = attempt;
  return attempt;
}

export function onSessionEnd(listener: () => void): () => void {
  endListeners.add(listener);
  return () => {
    endListeners.delete(listener);
  };
}

export function resetSessionForTests(): void {
  generation += 1;
  session = null;
  inFlight = null;
  clearTimeout(timer);
  timer = undefined;
  endListeners.clear();
}

async function requestRefresh(started: number): Promise<RefreshOutcome> {
  try {
    const response = await fetch(REFRESH_URL, { method: 'POST', credentials: 'same-origin' });
    if (started !== generation) {
      return 'signed-out';
    }
    if (response.status === 401) {
      endSession();
      return 'signed-out';
    }
    if (!response.ok) {
      return 'unavailable';
    }
    const token = (await response.json()) as TokenResponse;
    if (started !== generation) {
      return 'signed-out';
    }
    startSession(token);
    return 'refreshed';
  } catch {
    return 'unavailable';
  }
}
