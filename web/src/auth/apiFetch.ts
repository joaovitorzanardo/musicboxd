import { accessToken, isAccessTokenExpired, refreshSession, type RefreshOutcome } from './session';

/** `anonymous`: public auth calls (login, register…) that must not send a token or trigger a refresh. */
export type ApiRequestInit = RequestInit & { anonymous?: boolean };

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);
const noop = () => {};
let authRequired: () => void = noop;

/** The app sets this to "go to sign-up". Returns a function that unregisters it. */
export function setAuthRequiredHandler(handler: () => void): () => void {
  authRequired = handler;
  return () => {
    if (authRequired === handler) {
      authRequired = noop;
    }
  };
}

/**
 * fetch for /api/v1. Sends the in-memory access token. If the token is already expired it refreshes first.
 * On a 401 it refreshes once (single-flight, shared with every other caller) and retries once.
 * A write that still ends in 401 is a Guest acting, so the auth-required handler runs (MBD-22).
 * Bodies must be strings (JSON), because a retry sends the same init again.
 */
export async function apiFetch(path: string, init: ApiRequestInit = {}): Promise<Response> {
  const { anonymous = false, ...request } = init;
  if (anonymous) {
    return fetch(path, withHeaders(request, null));
  }

  if (isAccessTokenExpired()) {
    await refreshSession();
  }
  const sentWith = accessToken();
  const first = await fetch(path, withHeaders(request, sentWith));
  if (first.status !== 401) {
    return first;
  }

  // Another caller may have refreshed while this request was out; then just retry with the new token.
  const current = accessToken();
  const outcome: RefreshOutcome = current !== null && current !== sentWith ? 'refreshed' : await refreshSession();
  const final = outcome === 'refreshed' ? await fetch(path, withHeaders(request, accessToken())) : first;

  const isWrite = !SAFE_METHODS.has((request.method ?? 'GET').toUpperCase());
  if (final.status === 401 && isWrite && outcome !== 'unavailable') {
    authRequired();
  }
  return final;
}

function withHeaders(request: RequestInit, token: string | null): RequestInit {
  const headers = new Headers(request.headers);
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  if (typeof request.body === 'string' && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json');
  }
  return { ...request, headers, credentials: 'same-origin' };
}
