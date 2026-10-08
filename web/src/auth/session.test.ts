import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  REFRESH_LEAD_MS,
  accessToken,
  endSession,
  isAccessTokenExpired,
  onSessionEnd,
  refreshSession,
  startSession,
} from './session';

const token = (accessToken: string, expiresIn = 900) => ({ accessToken, tokenType: 'Bearer', expiresIn });
const ok = (accessToken: string) =>
  new Response(JSON.stringify(token(accessToken)), { status: 200, headers: { 'Content-Type': 'application/json' } });

describe('session', () => {
  beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn());
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('shares one refresh request between concurrent callers', async () => {
    vi.mocked(fetch).mockResolvedValue(ok('a1'));

    const [first, second] = await Promise.all([refreshSession(), refreshSession()]);

    expect(first).toBe('refreshed');
    expect(second).toBe('refreshed');
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(fetch).toHaveBeenCalledWith('/api/v1/auth/refresh', { method: 'POST', credentials: 'same-origin' });
    expect(accessToken()).toBe('a1');
  });

  it('ends the session and notifies listeners when the refresh cookie is refused', async () => {
    startSession(token('a1'));
    const ended = vi.fn();
    onSessionEnd(ended);
    vi.mocked(fetch).mockResolvedValue(new Response(null, { status: 401 }));

    expect(await refreshSession()).toBe('signed-out');
    expect(accessToken()).toBeNull();
    expect(ended).toHaveBeenCalledTimes(1);
  });

  it('keeps the session when the refresh cannot reach the API', async () => {
    startSession(token('a1'));
    vi.mocked(fetch).mockRejectedValueOnce(new TypeError('Failed to fetch'));
    expect(await refreshSession()).toBe('unavailable');

    vi.mocked(fetch).mockResolvedValueOnce(new Response('', { status: 502 }));
    expect(await refreshSession()).toBe('unavailable');

    expect(accessToken()).toBe('a1');
  });

  it('refreshes proactively one minute before the access token expires', async () => {
    vi.useFakeTimers();
    vi.mocked(fetch).mockResolvedValue(ok('a2'));
    startSession(token('a1', 900));

    await vi.advanceTimersByTimeAsync(900_000 - REFRESH_LEAD_MS - 1);
    expect(fetch).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    expect(fetch).toHaveBeenCalledTimes(1);
    expect(accessToken()).toBe('a2');
  });

  it('reports the token as expired once its lifetime has passed', () => {
    vi.useFakeTimers();
    expect(isAccessTokenExpired()).toBe(false); // no session at all is not "expired"
    startSession(token('a1', 900));
    expect(isAccessTokenExpired()).toBe(false);
    vi.setSystemTime(Date.now() + 900_000);
    expect(isAccessTokenExpired()).toBe(true);
  });

  it('ignores a refresh that lands after the session was ended', async () => {
    startSession(token('a1'));
    let respond!: (response: Response) => void;
    vi.mocked(fetch).mockReturnValue(new Promise((resolve) => (respond = resolve)));

    const pending = refreshSession();
    endSession(); // logout while the refresh is in flight
    respond(ok('a2'));

    expect(await pending).toBe('signed-out');
    expect(accessToken()).toBeNull();
  });

  it('starts a fresh refresh after the session was ended mid-flight', async () => {
    let respond!: (response: Response) => void;
    vi.mocked(fetch).mockReturnValueOnce(new Promise((resolve) => (respond = resolve)));
    const stale = refreshSession();
    endSession();

    vi.mocked(fetch).mockResolvedValueOnce(ok('a3'));
    expect(await refreshSession()).toBe('refreshed');
    respond(ok('a2'));
    await stale;

    expect(accessToken()).toBe('a3');
  });
});
