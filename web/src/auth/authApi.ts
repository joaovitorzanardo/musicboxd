import { apiFetch } from './apiFetch';
import type { Account, Problem, TokenResponse } from './types';

/** Problem `type`s the screens react to (see the api's *Exception classes). */
export const PROBLEM_TYPES = {
  emailNotVerified: 'urn:musicboxd:problem:email-not-verified',
  emailTaken: 'urn:musicboxd:problem:email-taken',
  usernameTaken: 'urn:musicboxd:problem:username-taken',
} as const;

export class ApiError extends Error {
  readonly problem: Problem;

  constructor(problem: Problem) {
    super(problem.detail ?? `Request failed with status ${problem.status}`);
    this.name = 'ApiError';
    this.problem = problem;
  }

  get status(): number {
    return this.problem.status;
  }

  get type(): string | undefined {
    return this.problem.type;
  }
}

export async function register(input: { username: string; email: string; password: string }): Promise<Account> {
  const response = await expectOk(
    await apiFetch('/api/v1/auth/register', { method: 'POST', body: JSON.stringify(input), anonymous: true }),
  );
  return (await response.json()) as Account;
}

export async function login(email: string, password: string): Promise<TokenResponse> {
  const response = await expectOk(
    await apiFetch('/api/v1/auth/login', { method: 'POST', body: JSON.stringify({ email, password }), anonymous: true }),
  );
  return (await response.json()) as TokenResponse;
}

/** Revokes this session's refresh token and clears the cookie; the cookie is the credential. */
export async function logout(): Promise<void> {
  await expectOk(await apiFetch('/api/v1/auth/refresh', { method: 'DELETE', anonymous: true }));
}

export async function resendVerificationEmail(email: string): Promise<void> {
  await expectOk(
    await apiFetch('/api/v1/auth/verification-email', { method: 'POST', body: JSON.stringify({ email }), anonymous: true }),
  );
}

/** The emailed link's token. 200 also when the link was already used; 400 when unknown, replaced or expired. */
export async function verifyEmail(token: string): Promise<void> {
  await expectOk(await apiFetch(`/api/v1/auth/verify?${new URLSearchParams({ token })}`, { anonymous: true }));
}

export async function fetchMe(): Promise<Account> {
  const response = await expectOk(await apiFetch('/api/v1/accounts/me'));
  return (await response.json()) as Account;
}

async function expectOk(response: Response): Promise<Response> {
  if (response.ok) {
    return response;
  }
  let body: Partial<Problem> = {};
  try {
    body = ((await response.json()) ?? {}) as Partial<Problem>;
  } catch {
    // Not JSON (an nginx error page, say): the status is all we have.
  }
  throw new ApiError({ ...body, status: response.status });
}
