import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiFetch, setAuthRequiredHandler } from './apiFetch';
import { startSession } from './session';
import { authHeader, fakeApi, json, problem, tokenResponse } from '../test/fakeApi';

const token = (accessToken: string, expiresIn = 900) => ({ accessToken, tokenType: 'Bearer', expiresIn });

describe('apiFetch', () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('sends the in-memory access token and JSON content type', async () => {
    startSession(token('a1'));
    const { calls } = fakeApi({ 'POST /api/v1/things': () => json(201, {}) });

    await apiFetch('/api/v1/things', { method: 'POST', body: '{}' });

    expect(authHeader(calls[0])).toBe('Bearer a1');
    expect(new Headers(calls[0].init.headers).get('Content-Type')).toBe('application/json');
    expect(calls[0].init.credentials).toBe('same-origin');
  });

  it('refreshes once on a 401 and retries with the new token', async () => {
    startSession(token('a1'));
    const { calls } = fakeApi({
      'GET /api/v1/accounts/me': [() => problem(401), () => json(200, { username: 'ana' })],
      'POST /api/v1/auth/refresh': () => tokenResponse('a2'),
    });

    const response = await apiFetch('/api/v1/accounts/me');

    expect(response.status).toBe(200);
    expect(calls.map((c) => `${c.method} ${c.url}`)).toEqual([
      'GET /api/v1/accounts/me',
      'POST /api/v1/auth/refresh',
      'GET /api/v1/accounts/me',
    ]);
    expect(authHeader(calls[2])).toBe('Bearer a2');
  });

  it('refreshes first when the token already expired (the laptop slept)', async () => {
    vi.useFakeTimers({ toFake: ['Date'] });
    startSession(token('a1', 900));
    vi.setSystemTime(Date.now() + 901_000);
    const { calls } = fakeApi({
      'POST /api/v1/auth/refresh': () => tokenResponse('a2'),
      'GET /api/v1/accounts/me': () => json(200, {}),
    });

    await apiFetch('/api/v1/accounts/me');

    expect(calls.map((c) => c.url)).toEqual(['/api/v1/auth/refresh', '/api/v1/accounts/me']);
    expect(authHeader(calls[1])).toBe('Bearer a2');
  });

  it('shares one refresh between parallel 401s', async () => {
    startSession(token('a1'));
    const { calls } = fakeApi({
      'GET /api/v1/a': [() => problem(401), () => json(200, {})],
      'GET /api/v1/b': [() => problem(401), () => json(200, {})],
      'POST /api/v1/auth/refresh': () => tokenResponse('a2'),
    });

    await Promise.all([apiFetch('/api/v1/a'), apiFetch('/api/v1/b')]);

    expect(calls.filter((c) => c.url === '/api/v1/auth/refresh')).toHaveLength(1);
  });

  it('retries without refreshing again when another caller already did', async () => {
    startSession(token('a1'));
    let answer401!: () => void;
    const { calls } = fakeApi({
      'GET /api/v1/slow': [
        () => new Promise<Response>((resolve) => (answer401 = () => resolve(problem(401)))),
        () => json(200, {}),
      ],
    });

    const slow = apiFetch('/api/v1/slow'); // sent with a1
    await vi.waitFor(() => expect(calls).toHaveLength(1));
    startSession(token('a2')); // another caller refreshed meanwhile
    answer401();
    await slow;

    expect(calls.map((c) => c.url)).toEqual(['/api/v1/slow', '/api/v1/slow']);
    expect(authHeader(calls[1])).toBe('Bearer a2');
  });

  it('sends a Guest whose write gets a 401 to the auth-required handler', async () => {
    const handler = vi.fn();
    setAuthRequiredHandler(handler);
    fakeApi({
      'POST /api/v1/ratings': () => problem(401),
      'POST /api/v1/auth/refresh': () => problem(401, 'urn:musicboxd:problem:invalid-refresh-token'),
    });

    const response = await apiFetch('/api/v1/ratings', { method: 'POST', body: '{}' });

    expect(response.status).toBe(401);
    expect(handler).toHaveBeenCalledTimes(1);
  });

  it('never redirects on a GET 401', async () => {
    const handler = vi.fn();
    setAuthRequiredHandler(handler);
    fakeApi({
      'GET /api/v1/accounts/me': () => problem(401),
      'POST /api/v1/auth/refresh': () => problem(401),
    });

    await apiFetch('/api/v1/accounts/me');

    expect(handler).not.toHaveBeenCalled();
  });

  it('does not send anyone to sign-up when the refresh is unavailable', async () => {
    const handler = vi.fn();
    setAuthRequiredHandler(handler);
    startSession(token('a1'));
    fakeApi({
      'POST /api/v1/ratings': () => problem(401),
      'POST /api/v1/auth/refresh': () => Promise.reject(new TypeError('Failed to fetch')),
    });

    const response = await apiFetch('/api/v1/ratings', { method: 'POST', body: '{}' });

    expect(response.status).toBe(401);
    expect(handler).not.toHaveBeenCalled();
  });

  it('anonymous calls carry no bearer token and never refresh', async () => {
    startSession(token('a1'));
    const { calls } = fakeApi({ 'POST /api/v1/auth/login': () => problem(401) });

    const response = await apiFetch('/api/v1/auth/login', { method: 'POST', body: '{}', anonymous: true });

    expect(response.status).toBe(401);
    expect(calls).toHaveLength(1);
    expect(authHeader(calls[0])).toBeNull();
  });

  it('unregistering a handler stops it from being called', async () => {
    const handler = vi.fn();
    const unregister = setAuthRequiredHandler(handler);
    unregister();
    fakeApi({ 'POST /api/v1/ratings': () => problem(401), 'POST /api/v1/auth/refresh': () => problem(401) });

    await apiFetch('/api/v1/ratings', { method: 'POST', body: '{}' });

    expect(handler).not.toHaveBeenCalled();
  });
});
