import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, PROBLEM_TYPES, login, register, verifyEmail } from './authApi';
import { startSession } from './session';
import { authHeader, fakeApi, json, problem } from '../test/fakeApi';

describe('authApi', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('register posts JSON without a bearer token even when signed in', async () => {
    startSession({ accessToken: 'a1', tokenType: 'Bearer', expiresIn: 900 });
    const { calls } = fakeApi({
      'POST /api/v1/auth/register': () => json(201, { id: '1', email: 'ana@exemplo.com', username: 'ana' }),
    });

    const account = await register({ username: 'ana', email: 'ana@exemplo.com', password: 'correct-horse' });

    expect(account.username).toBe('ana');
    expect(JSON.parse(calls[0].init.body as string)).toEqual({
      username: 'ana',
      email: 'ana@exemplo.com',
      password: 'correct-horse',
    });
    expect(authHeader(calls[0])).toBeNull();
  });

  it('turns a Problem Details answer into an ApiError with its type', async () => {
    fakeApi({ 'POST /api/v1/auth/register': () => problem(409, PROBLEM_TYPES.emailTaken) });

    const error = await register({ username: 'ana', email: 'a@b.c', password: '12345678' }).catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(409);
    expect((error as ApiError).type).toBe('urn:musicboxd:problem:email-taken');
  });

  it('keeps the HTTP status when the error body is not JSON', async () => {
    fakeApi({ 'POST /api/v1/auth/login': () => new Response('<html>Bad gateway</html>', { status: 502 }) });

    const error = await login('a@b.c', 'x').catch((e: unknown) => e);

    expect((error as ApiError).status).toBe(502);
    expect((error as ApiError).type).toBeUndefined();
  });
  it('verifyEmail sends the token to the verify endpoint without a bearer token', async () => {
    startSession({ accessToken: 'a1', tokenType: 'Bearer', expiresIn: 900 });
    const { calls } = fakeApi({ 'GET /api/v1/auth/verify': () => json(200, { status: 'verified' }) });

    await verifyEmail('abc_DEF-123');

    expect(calls).toHaveLength(1);
    expect(calls[0].method).toBe('GET');
    expect(calls[0].url).toBe('/api/v1/auth/verify?token=abc_DEF-123');
    expect(authHeader(calls[0])).toBeNull();
  });

  it('verifyEmail encodes the token as a single query value', async () => {
    const { calls } = fakeApi({ 'GET /api/v1/auth/verify': () => json(200, { status: 'verified' }) });

    await verifyEmail('a&b=c #d+e');

    const sent = new URLSearchParams(calls[0].url.split('?')[1]);
    expect([...sent.keys()]).toEqual(['token']);
    expect(sent.get('token')).toBe('a&b=c #d+e');
  });

  it('verifyEmail rejects with a 400 ApiError for an invalid or expired link', async () => {
    fakeApi({ 'GET /api/v1/auth/verify': () => problem(400) });

    await expect(verifyEmail('expired')).rejects.toMatchObject({ name: 'ApiError', status: 400 });
  });
});
