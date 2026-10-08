/** Body of POST /api/v1/auth/login and /refresh. */
export type TokenResponse = { accessToken: string; tokenType: string; expiresIn: number };

/** GET /api/v1/accounts/me and POST /api/v1/auth/register. */
export type Account = { id: string; email: string; username: string };

/** RFC 9457 Problem Details as the API sends them. `status` is always the HTTP status. */
export type Problem = { status: number; type?: string; title?: string; detail?: string };
