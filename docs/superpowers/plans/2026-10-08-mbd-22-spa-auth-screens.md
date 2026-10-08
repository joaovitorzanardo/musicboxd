# MBD-22 SPA Auth Screens and the Guest Boundary — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The SPA gets three auth screens (Entrar, Criar conta, Verifique seu email). It keeps the access token in memory and renews it with the refresh cookie before and after it expires (15 min). It also sends a Guest whose write gets a 401 to sign-up instead of failing silently, and it lets a signed-in person log out.

**Architecture:** A small `web/src/auth/` module with no React in its core. `session.ts` holds the access token in module memory, refreshes **single-flight** through `POST /api/v1/auth/refresh` (the browser sends the HttpOnly cookie), and schedules a proactive refresh 60 s before expiry. `apiFetch.ts` wraps `fetch`. It attaches the bearer token, refreshes first when the token is already expired (for example after the laptop slept), and on a 401 refreshes once and retries once. A write that still gets a 401 calls the "auth required" handler. `AuthProvider.tsx` turns this into React state (`loading | guest | signed-in`), restores the session on page load, and wires the handler to `navigate('/cadastro?next=…')`. The screens are plain React and CSS built on the DESIGN.md tokens, ported from the approved mockup.

**Tech Stack:** React 19.3, Vite 8, TypeScript 7, react-router 7 (declarative mode, new), Vitest 5 + Testing Library + `@testing-library/user-event` (new). Backend: Java 25 / Spring Boot 4.1 (one small change to the 409 Problem Details), JUnit 5 / MockMvc / Testcontainers.

**Spec:** Jira MBD-22 / `_bmad-output/initiative-musicboxd/epic-contas-acesso/story-spa-auth-screens-and-the-guest-boundary.md`. Screens: `_bmad-output/planning-artifacts/ux-designs/ux-teste-2026-09-26/mockups/auth.html` (illustration; `EXPERIENCE.md` and `DESIGN.md` in the same folder win on conflict). Architecture: `ARCHITECTURE-SPINE.md` AD-7 and AD-8. The refresh flow is a user requirement added on top of the ticket: "implement the refresh token when the first one expires after 15 minutes."

**Branch:** if MBD-19, MBD-20 and MBD-21 are merged, `git switch main && git pull && git switch -c story/mbd-22-spa-auth`. If not, branch from the newest unmerged story branch (`story/mbd-21-staff-role`). Commit messages end with `(MBD-22)` and the `Co-Authored-By` trailer. Run npm from `web/`. Run Gradle from `api/` (`./gradlew` in Git Bash, `.\gradlew.bat` in PowerShell). Docker must be running for Testcontainers.

## Decisions (defaults chosen for this story)

| Question | Decision |
|---|---|
| Routes (UX left them open, EXPERIENCE.md item i) | `/entrar`, `/cadastro`, `/verifique-email`, in Portuguese like `/ouvir-depois`. |
| Router | `react-router` 7 in declarative mode (`BrowserRouter`, `Routes`). nginx already falls back to `index.html`. |
| HTTP layer | A hand-written `apiFetch`. The generated hey-api client is stale (only `health`) and has no 401-retry hook. When generated SDK calls appear, give them the same token through `client.setConfig`. |
| Where the access token lives | Module memory only (AD-8). Never `localStorage`. A hard refresh drops it and the cookie brings it back. |
| Expiry clock | `expiresIn` (seconds) counted from when the response arrived. The JWT `exp` is not decoded, so a skewed client clock does not matter. |
| When to refresh | (1) On page load. (2) Proactively, 60 s before expiry. (3) Before sending, if the token is already expired. (4) On a 401, once, then retry once. All four share one in-flight request (single-flight, AD-8). |
| Refresh answers 401 | Session over: state becomes `guest`. |
| Refresh fails on the network or with 5xx | Not a logout. The session is kept and the request returns its own error. The Guest boundary is **not** triggered. |
| Guest boundary | A non-GET request whose final answer is 401 (after a refresh attempt) calls the handler, which goes to `/cadastro?next=<current path+search>`. GETs never redirect. |
| `next` after login | Only same-origin paths (`/x`, not `//x` and not `/\x`). Otherwise `/`. |
| Logout UI | A "Sair" button on the placeholder home page. The top bar and avatar menu belong to a later story. Logout calls `DELETE /api/v1/auth/refresh`, ends the local session even if that call fails, and goes to `/`. |
| 409 on register | The backend gives each 409 its own Problem type (`urn:musicboxd:problem:email-taken`, `…:username-taken`), so the SPA puts the message on the correct field without parsing English `detail`. |
| Username help text | The mockup says "Letras, números, ponto e sublinhado", but the API accepts `[A-Za-z0-9_]{3,20}` (no dot). The API rule wins. The copy is "Aparece no seu perfil. De 3 a 20 letras, números ou sublinhado." A leading `@` that someone types is stripped. |
| Theme | Dark only (the default). The toggle lives in the avatar menu, which comes in a later story. |
| Verification email link | Still points at `GET /api/v1/auth/verify` (JSON). A SPA landing page is a follow-up, not one of the three screens. |
| Dev server | Vite proxies `/api` to `http://localhost:8080`, so the cookie is same-origin. Chrome and Firefox accept the `Secure` cookie on `http://localhost`. Safari does not, so use Chrome or Firefox for local testing. |

## Global Constraints

- Access token in memory only; never `localStorage`, `sessionStorage` or a non-HttpOnly cookie (AD-8).
- Refresh is single-flight (AD-8): at most one `POST /api/v1/auth/refresh` in flight per tab.
- Refresh cookie: `musicboxd_refresh`, path `/api/v1/auth/refresh`. The SPA never reads it; every request uses `credentials: 'same-origin'`.
- Copy is pt-BR. It comes from the mockup or EXPERIENCE.md where they define it. New strings are marked `[ASSUMPTION]` in this plan.
- Password: at least 8 characters, no "confirmar senha", no "esqueci a senha". Username `[A-Za-z0-9_]{3,20}`.
- Visible focus is coral (`#FF5A3C`). Password toggle `aria-label` is "Mostrar senha" or "Ocultar senha".
- `package.json` pins exact versions (no `^`). Install with `--save-exact`.
- No business rule lives only in the client (AD-7). Client validation mirrors server rules for fast feedback; the server is the authority.

## Review Focus

1. **Logout while a refresh is in flight.** The late refresh response must not bring the session back. Pinned in Task 2 (`ignores a refresh that lands after the session was ended`).
2. **The laptop slept past 15 minutes**, so the proactive timer never fired. The next request must refresh *before* sending instead of failing. Pinned in Task 3 (`refreshes first when the token already expired`).
3. **API unreachable during refresh.** The user must not be logged out or sent to sign-up. Pinned in Task 2 (`keeps the session when the refresh cannot reach the API`) and Task 3 (`does not send anyone to sign-up when the refresh is unavailable`).
4. **Parallel 401s and late 401s.** A burst does one refresh. A 401 that arrives after another caller already refreshed retries with the new token without rotating again. Pinned in Task 3 (`shares one refresh between parallel 401s`, `retries without refreshing again when another caller already did`).
5. **Open redirect through `?next=`** (`//evil.example`, `https://evil.example`, `/\evil.example`) must land on `/`. Pinned in Task 4 (`safeNext` tests).

---

## File Structure

| File | Responsibility |
|---|---|
| `api/.../accounts/EmailTakenException.java`, `api/.../profiles/UsernameTakenException.java` | Add a Problem `type` and `title` to each 409. |
| `web/vite.config.ts` | Dev proxy for `/api`. |
| `web/index.html` | `lang="pt-BR"`, Inter and Outfit fonts. |
| `web/src/vite-env.d.ts` | Vite client types (for the `.svg` import). |
| `web/src/styles/tokens.css` | DESIGN.md color and font tokens, base body styles. |
| `web/src/styles/auth.css` | Auth card, fields, alert and button styles (ported from the mockup). |
| `web/src/assets/musicboxd-logo-dark.svg` | Copied from `musicboxd-brand/`. |
| `web/src/auth/types.ts` | `TokenResponse`, `Account`, `Problem`. |
| `web/src/auth/session.ts` | In-memory token, single-flight refresh, proactive timer, session-end listeners. |
| `web/src/auth/apiFetch.ts` | Bearer, refresh before or after a 401, retry, Guest-boundary handler. |
| `web/src/auth/authApi.ts` | `register`, `login`, `logout`, `resendVerificationEmail`, `fetchMe`, `ApiError`, `PROBLEM_TYPES`. |
| `web/src/auth/safeNext.ts` | Validate the `next` redirect target. |
| `web/src/auth/AuthProvider.tsx` | React state, bootstrap, `signIn`, `signOut`, Guest-boundary navigation, `useAuth`, `GuestOnly`. |
| `web/src/ui/AuthLayout.tsx` | Logo above a centered card, no top bar. |
| `web/src/ui/Field.tsx` | `TextField` and `PasswordField` (eye toggle). |
| `web/src/ui/Alert.tsx` | Error alert at the top of the card. |
| `web/src/ui/icons.tsx` | Eye, eye-off and mail SVG icons. |
| `web/src/pages/LoginPage.tsx` | Entrar. |
| `web/src/pages/RegisterPage.tsx` | Criar conta. |
| `web/src/pages/VerifyEmailSentPage.tsx` | Verifique seu email, with resend. |
| `web/src/pages/HomePage.tsx` | Placeholder home: session bar (Entrar / Criar conta, or @user + Sair) and the health check. |
| `web/src/HealthStatus.tsx` | The current `App` body moved out unchanged. |
| `web/src/App.tsx` | `AppRoutes` and `App` (BrowserRouter + AuthProvider). |
| `web/src/test/fakeApi.ts`, `web/src/test/renderApp.tsx` | Test helpers: route-based fetch fake, app render at a path. |

---

### Task 1: Typed 409 Problem Details for a taken email or username

**Files:**
- Modify: `api/src/main/java/com/musicboxd/api/accounts/EmailTakenException.java`
- Modify: `api/src/main/java/com/musicboxd/api/profiles/UsernameTakenException.java`
- Test: `api/src/test/java/com/musicboxd/api/accounts/AuthFlowTest.java:174-184`

**Interfaces:**
- Produces: `409` bodies with `"type": "urn:musicboxd:problem:email-taken"` or `"type": "urn:musicboxd:problem:username-taken"`. The SPA (Task 4) matches on these strings.

- [ ] **Step 1: Write the failing test.** Replace `duplicateEmailOrUsernameIsAConflict` in `AuthFlowTest.java` with:

```java
	@Test
	void duplicateEmailOrUsernameIsAConflictWithItsOwnProblemType() throws Exception {
		String email = uniqueEmail();
		String username = uniqueUsername();
		register(email, PASSWORD, username).andExpect(status().isCreated());

		register(email.toUpperCase(), PASSWORD, uniqueUsername())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value(409))
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:email-taken"));
		register(uniqueEmail(), PASSWORD, username.toUpperCase())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:username-taken"));
	}
```

- [ ] **Step 2: Run it and confirm it fails.**
Run: `./gradlew test --tests com.musicboxd.api.accounts.AuthFlowTest`
Expected: FAIL. `$.type` is `about:blank`.

- [ ] **Step 3: Implement.** In `EmailTakenException.java`:

```java
package com.musicboxd.api.accounts;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The type lets the SPA (MBD-22) show the message under the email field. */
public class EmailTakenException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:email-taken");

	public EmailTakenException() {
		super(HttpStatus.CONFLICT, problem(), null);
	}

	private static ProblemDetail problem() {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "An account with this email already exists");
		problem.setType(TYPE);
		problem.setTitle("Email taken");
		return problem;
	}
}
```

In `UsernameTakenException.java`:

```java
package com.musicboxd.api.profiles;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The type lets the SPA (MBD-22) show the message under the username field. */
public class UsernameTakenException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:username-taken");

	public UsernameTakenException() {
		super(HttpStatus.CONFLICT, problem(), null);
	}

	private static ProblemDetail problem() {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Username is already taken");
		problem.setType(TYPE);
		problem.setTitle("Username taken");
		return problem;
	}
}
```

Also update the `@ApiResponse(responseCode = "409", …)` description on `AuthController.register` to `"Email or username already taken (type urn:musicboxd:problem:email-taken or …:username-taken)"`.

- [ ] **Step 4: Run the accounts tests and confirm they pass.**
Run: `./gradlew test --tests 'com.musicboxd.api.accounts.*' --tests 'com.musicboxd.api.profiles.*'`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add api/src/main/java/com/musicboxd/api/accounts/EmailTakenException.java api/src/main/java/com/musicboxd/api/profiles/UsernameTakenException.java api/src/main/java/com/musicboxd/api/accounts/AuthController.java api/src/test/java/com/musicboxd/api/accounts/AuthFlowTest.java
git commit -m "Give the email-taken and username-taken 409s their own Problem types (MBD-22)"
```

---

### Task 2: In-memory session with single-flight and proactive refresh

**Files:**
- Create: `web/src/auth/types.ts`
- Create: `web/src/auth/session.ts`
- Modify: `web/src/test/setup.ts`
- Test: `web/src/auth/session.test.ts`

**Interfaces:**
- Produces (`types.ts`): `TokenResponse = { accessToken: string; tokenType: string; expiresIn: number }`, `Account = { id: string; email: string; username: string }`, `Problem = { status: number; type?: string; title?: string; detail?: string }`.
- Produces (`session.ts`): `REFRESH_URL`, `REFRESH_LEAD_MS = 60_000`, `type RefreshOutcome = 'refreshed' | 'signed-out' | 'unavailable'`, `accessToken(): string | null`, `isAccessTokenExpired(): boolean`, `startSession(token: TokenResponse): void`, `endSession(): void`, `refreshSession(): Promise<RefreshOutcome>`, `onSessionEnd(listener: () => void): () => void`, `resetSessionForTests(): void`.

- [ ] **Step 1: Write the failing tests** in `web/src/auth/session.test.ts`:

```ts
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
```

Add the reset to `web/src/test/setup.ts`:

```ts
import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';
import { resetSessionForTests } from '../auth/session';

afterEach(() => {
  cleanup();
  resetSessionForTests();
});
```

- [ ] **Step 2: Run the tests and confirm they fail.**
Run: `npm test -- src/auth/session.test.ts`
Expected: FAIL. `Failed to resolve import "./session"`.

- [ ] **Step 3: Implement.** `web/src/auth/types.ts`:

```ts
/** Body of POST /api/v1/auth/login and /refresh. */
export type TokenResponse = { accessToken: string; tokenType: string; expiresIn: number };

/** GET /api/v1/accounts/me and POST /api/v1/auth/register. */
export type Account = { id: string; email: string; username: string };

/** RFC 9457 Problem Details as the API sends them. `status` is always the HTTP status. */
export type Problem = { status: number; type?: string; title?: string; detail?: string };
```

`web/src/auth/session.ts`:

```ts
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
```

- [ ] **Step 4: Run the tests and confirm they pass.**
Run: `npm test -- src/auth/session.test.ts`
Expected: PASS (7 tests).

- [ ] **Step 5: Commit.**

```bash
git add web/src/auth/types.ts web/src/auth/session.ts web/src/auth/session.test.ts web/src/test/setup.ts
git commit -m "Hold the access token in memory and refresh it single-flight, one minute before expiry (MBD-22)"
```

---

### Task 3: `apiFetch`: bearer, refresh-and-retry, Guest boundary

**Files:**
- Create: `web/src/auth/apiFetch.ts`
- Create: `web/src/test/fakeApi.ts`
- Test: `web/src/auth/apiFetch.test.ts`

**Interfaces:**
- Consumes: `accessToken`, `isAccessTokenExpired`, `refreshSession`, `startSession` from Task 2.
- Produces: `type ApiRequestInit = RequestInit & { anonymous?: boolean }`, `apiFetch(path: string, init?: ApiRequestInit): Promise<Response>`, `setAuthRequiredHandler(handler: () => void): () => void` (it returns the unregister function).
- Produces (test helper `fakeApi.ts`): `fakeApi(routes: Record<string, Handler | Handler[]>): { calls: Call[] }`, where the key is `"METHOD /path"` and an array is used in order, with the last entry repeating. Also `json(status, body)`, `problem(status, type?)`, `tokenResponse(accessToken?, expiresIn?)`, `authHeader(call)`.

- [ ] **Step 1: Write the test helper** `web/src/test/fakeApi.ts`:

```ts
import { vi } from 'vitest';

export type Call = { method: string; url: string; init: RequestInit };
type Handler = (call: Call) => Response | Promise<Response>;

/**
 * Stubs global fetch with a route table keyed "METHOD /path". An array of handlers answers in order
 * and repeats its last entry. Unknown routes reject, so an unexpected call fails the test loudly.
 */
export function fakeApi(routes: Record<string, Handler | Handler[]>) {
  const calls: Call[] = [];
  const queues = new Map(Object.entries(routes).map(([key, value]) => [key, Array.isArray(value) ? [...value] : [value]]));
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.pathname : new URL(input.url).pathname;
      const call = { method: (init.method ?? 'GET').toUpperCase(), url, init };
      calls.push(call);
      const queue = queues.get(`${call.method} ${call.url}`);
      if (!queue) {
        throw new Error(`Unexpected request ${call.method} ${call.url}`);
      }
      const handler = queue.length > 1 ? queue.shift()! : queue[0];
      return handler(call);
    }),
  );
  return { calls };
}

export const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

export const problem = (status: number, type = 'about:blank') =>
  new Response(JSON.stringify({ type, status }), { status, headers: { 'Content-Type': 'application/problem+json' } });

export const tokenResponse = (accessToken = 'access-1', expiresIn = 900) =>
  json(200, { accessToken, tokenType: 'Bearer', expiresIn });

export const authHeader = (call: Call) => new Headers(call.init.headers).get('Authorization');
```

- [ ] **Step 2: Write the failing tests** in `web/src/auth/apiFetch.test.ts`:

```ts
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
```

- [ ] **Step 3: Run the tests and confirm they fail.**
Run: `npm test -- src/auth/apiFetch.test.ts`
Expected: FAIL. `Failed to resolve import "./apiFetch"`.

- [ ] **Step 4: Implement** `web/src/auth/apiFetch.ts`:

```ts
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
```

Add `setAuthRequiredHandler(noop)` to the test reset: in `session.ts` nothing changes. In `web/src/test/setup.ts`, extend `afterEach`:

```ts
import { setAuthRequiredHandler } from '../auth/apiFetch';
// inside afterEach, after resetSessionForTests():
  setAuthRequiredHandler(() => {});
```

- [ ] **Step 5: Run the tests and confirm they pass.**
Run: `npm test -- src/auth`
Expected: PASS (session 7, apiFetch 10).

- [ ] **Step 6: Commit.**

```bash
git add web/src/auth/apiFetch.ts web/src/auth/apiFetch.test.ts web/src/test/fakeApi.ts web/src/test/setup.ts
git commit -m "Wrap fetch with bearer, refresh-and-retry on 401, and the Guest boundary for writes (MBD-22)"
```

---

### Task 4: Auth API calls, Problem parsing and `safeNext`

**Files:**
- Create: `web/src/auth/authApi.ts`
- Create: `web/src/auth/safeNext.ts`
- Test: `web/src/auth/authApi.test.ts`, `web/src/auth/safeNext.test.ts`

**Interfaces:**
- Consumes: `apiFetch` (Task 3), types (Task 2).
- Produces: `PROBLEM_TYPES = { emailNotVerified, emailTaken, usernameTaken }`, `class ApiError extends Error { problem: Problem; status: number; type?: string }`, `register(input: { username: string; email: string; password: string }): Promise<Account>`, `login(email: string, password: string): Promise<TokenResponse>`, `logout(): Promise<void>`, `resendVerificationEmail(email: string): Promise<void>`, `fetchMe(): Promise<Account>`, `safeNext(raw: string | null): string`.

- [ ] **Step 1: Write the failing tests.** `web/src/auth/safeNext.test.ts`:

```ts
import { describe, expect, it } from 'vitest';
import { safeNext } from './safeNext';

describe('safeNext', () => {
  it.each([
    ['/album/42', '/album/42'],
    ['/u/ana?tab=albuns', '/u/ana?tab=albuns'],
  ])('keeps the same-origin path %s', (raw, expected) => {
    expect(safeNext(raw)).toBe(expected);
  });

  it.each([null, '', 'album/42', '//evil.example', '/\\evil.example', 'https://evil.example', 'javascript:alert(1)'])(
    'falls back to / for %s',
    (raw) => {
      expect(safeNext(raw)).toBe('/');
    },
  );
});
```

`web/src/auth/authApi.test.ts`:

```ts
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, PROBLEM_TYPES, login, register } from './authApi';
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
});
```

- [ ] **Step 2: Run the tests and confirm they fail.**
Run: `npm test -- src/auth/authApi.test.ts src/auth/safeNext.test.ts`
Expected: FAIL. Unresolved imports.

- [ ] **Step 3: Implement.** `web/src/auth/safeNext.ts`:

```ts
/** Where to go after login: only a path on this origin. `//host` and `/\host` are other origins to a browser. */
export function safeNext(raw: string | null): string {
  if (!raw || !raw.startsWith('/') || raw.startsWith('//') || raw.startsWith('/\\')) {
    return '/';
  }
  return raw;
}
```

`web/src/auth/authApi.ts`:

```ts
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
```

- [ ] **Step 4: Run the tests and confirm they pass.**
Run: `npm test -- src/auth`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add web/src/auth/authApi.ts web/src/auth/authApi.test.ts web/src/auth/safeNext.ts web/src/auth/safeNext.test.ts
git commit -m "Add the auth API calls, Problem Details errors and a same-origin next guard (MBD-22)"
```

---

### Task 5: Routing, AuthProvider, styles and UI primitives

This task wires the app shell. It ships the provider, routes, the placeholder home with logout, and the shared auth UI. The three screens follow in Tasks 6–8, so `AppRoutes` points at stub pages for now (each stub is replaced in its own task).

**Files:**
- Modify: `web/package.json`, `web/package-lock.json` (via npm), `web/vite.config.ts`, `web/index.html`, `web/src/main.tsx`, `web/src/App.tsx`
- Rename: `web/src/App.test.tsx` → `web/src/HealthStatus.test.tsx`
- Create: `web/src/HealthStatus.tsx`, `web/src/vite-env.d.ts`, `web/src/auth/AuthProvider.tsx`, `web/src/pages/HomePage.tsx`, `web/src/pages/LoginPage.tsx`, `web/src/pages/RegisterPage.tsx`, `web/src/pages/VerifyEmailSentPage.tsx` (stubs), `web/src/ui/AuthLayout.tsx`, `web/src/ui/Field.tsx`, `web/src/ui/Alert.tsx`, `web/src/ui/icons.tsx`, `web/src/styles/tokens.css`, `web/src/styles/auth.css`, `web/src/assets/musicboxd-logo-dark.svg`, `web/src/test/renderApp.tsx`
- Test: `web/src/auth/AuthProvider.test.tsx`, `web/src/ui/Field.test.tsx`

**Interfaces:**
- Consumes: Tasks 2–4.
- Produces: `type AuthState = { status: 'loading' } | { status: 'guest' } | { status: 'signed-in'; account: Account }`. `useAuth(): { state: AuthState; signIn(email: string, password: string): Promise<void>; signOut(): Promise<void> }`. `GuestOnly({ children })`, which redirects signed-in people to `safeNext(?next)`. `AppRoutes({ children? })`. `AuthLayout({ titleId, centered?, children })`. `TextField(props)`, `PasswordField(props)` with props `{ id, label, value, onChange(value), type?, placeholder?, autoComplete?, prefix?, help?, error?: ReactNode, invalid?: boolean }`. `Alert({ title, children })`. `EyeIcon`, `EyeOffIcon`, `MailIcon`. Test helpers `renderApp(entry, extraRoutes?)`, `LocationProbe`.

- [ ] **Step 1: Install the dependencies (exact pins).**

```bash
cd web
npm install --save-exact react-router@7
npm install --save-dev --save-exact @testing-library/user-event@14
```

Check `package.json`: both entries are exact versions (no `^`).

- [ ] **Step 2: Move the health check out of `App`.** Create `web/src/HealthStatus.tsx` with the current `App.tsx` body. Keep the effect and the `HealthState` type unchanged; only the wrapper changes:

```tsx
// (imports, HealthState type and the useEffect block are copied verbatim from the old App.tsx)
export function HealthStatus() {
  // …same useState + useEffect as before…
  return (
    <>
      {health.kind === 'loading' && <p role="status">Checking API health…</p>}
      {health.kind === 'ok' && <p role="status">API status: {health.status}</p>}
      {health.kind === 'error' && <p role="alert">Could not reach the API: {health.message}</p>}
    </>
  );
}
```

`git mv web/src/App.test.tsx web/src/HealthStatus.test.tsx`. In it, change the import to `import { HealthStatus } from './HealthStatus';`, change each `render(<App />)` to `render(<HealthStatus />)`, and rename the `describe` to `'HealthStatus'`.

- [ ] **Step 3: Write the test helper** `web/src/test/renderApp.tsx`:

```tsx
import type { ReactNode } from 'react';
import { render } from '@testing-library/react';
import { MemoryRouter, useLocation } from 'react-router';
import { AuthProvider } from '../auth/AuthProvider';
import { AppRoutes } from '../App';

type Entry = string | { pathname: string; search?: string; state?: unknown };

/** The real routes and provider at a given URL. `extraRoutes` adds test-only <Route>s. */
export function renderApp(entry: Entry, extraRoutes?: ReactNode) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <AuthProvider>
        <AppRoutes>{extraRoutes}</AppRoutes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

export function LocationProbe() {
  const location = useLocation();
  return <p data-testid="location">{location.pathname + location.search}</p>;
}
```

- [ ] **Step 4: Write the failing tests.** `web/src/auth/AuthProvider.test.tsx` (the ticket's acceptance criteria):

```tsx
import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router';
import { apiFetch } from './apiFetch';
import { fakeApi, json, problem, tokenResponse } from '../test/fakeApi';
import { LocationProbe, renderApp } from '../test/renderApp';

const ANA = { id: '1', email: 'ana@exemplo.com', username: 'ana' };
const health = () => json(200, { status: 'ok' });

function RateButton() {
  return (
    <button type="button" onClick={() => void apiFetch('/api/v1/ratings', { method: 'POST', body: '{}' })}>
      Avaliar
    </button>
  );
}

describe('AuthProvider', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('restores the session from the refresh cookie on a hard refresh', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => tokenResponse('a1'),
      'GET /api/v1/accounts/me': () => json(200, ANA),
      'GET /api/v1/health': health,
    });

    renderApp('/');

    expect(await screen.findByText(/Conectado como @ana/)).toBeInTheDocument();
  });

  it('is a Guest after a hard refresh without a working refresh cookie', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401), 'GET /api/v1/health': health });

    renderApp('/');

    expect(await screen.findByRole('link', { name: 'Entrar' })).toHaveAttribute('href', '/entrar');
    expect(screen.getByRole('link', { name: 'Criar conta' })).toHaveAttribute('href', '/cadastro');
  });

  it('sends a Guest whose write gets a 401 to sign-up, remembering where they were', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401), 'POST /api/v1/ratings': () => problem(401) });
    const user = userEvent.setup();
    renderApp('/album/42', <Route path="/album/:id" element={<RateButton />} />);

    await user.click(await screen.findByRole('button', { name: 'Avaliar' }));

    expect(await screen.findByTestId('page')).toHaveTextContent('cadastro?next=/album/42');
  });

  it('signing out revokes the cookie and leaves a Guest', async () => {
    const { calls } = fakeApi({
      'POST /api/v1/auth/refresh': () => tokenResponse('a1'),
      'DELETE /api/v1/auth/refresh': () => new Response(null, { status: 204 }),
      'GET /api/v1/accounts/me': () => json(200, ANA),
      'GET /api/v1/health': health,
    });
    const user = userEvent.setup();
    renderApp('/');

    await user.click(await screen.findByRole('button', { name: 'Sair' }));

    expect(await screen.findByRole('link', { name: 'Entrar' })).toBeInTheDocument();
    expect(calls.some((c) => c.method === 'DELETE' && c.url === '/api/v1/auth/refresh')).toBe(true);
  });

  it('ends the local session even when the logout call fails', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => tokenResponse('a1'),
      'DELETE /api/v1/auth/refresh': () => Promise.reject(new TypeError('Failed to fetch')),
      'GET /api/v1/accounts/me': () => json(200, ANA),
      'GET /api/v1/health': health,
    });
    const user = userEvent.setup();
    renderApp('/');

    await user.click(await screen.findByRole('button', { name: 'Sair' }));

    expect(await screen.findByRole('link', { name: 'Entrar' })).toBeInTheDocument();
  });

  it('sends a signed-in person away from the auth screens', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => tokenResponse('a1'),
      'GET /api/v1/accounts/me': () => json(200, ANA),
    });

    renderApp('/entrar?next=%2Falbum%2F42', <Route path="/album/:id" element={<LocationProbe />} />);

    expect(await screen.findByTestId('location')).toHaveTextContent('/album/42');
  });
});
```

The stub pages render `<p data-testid="page">{pathname + search}</p>` (decoded with `decodeURIComponent`) until Tasks 6–8 replace them. That is why the Guest-boundary test reads `cadastro?next=/album/42`. Task 7 rewrites that assertion against the real page.

`web/src/ui/Field.test.tsx`:

```tsx
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useState } from 'react';
import { PasswordField, TextField } from './Field';

function Password() {
  const [value, setValue] = useState('segredo123');
  return <PasswordField id="p" label="Senha" value={value} onChange={setValue} help="Mínimo de 8 caracteres." />;
}

describe('Field', () => {
  it('shows and hides the password with a labelled eye button', async () => {
    const user = userEvent.setup();
    render(<Password />);
    const input = screen.getByLabelText('Senha');
    expect(input).toHaveAttribute('type', 'password');

    await user.click(screen.getByRole('button', { name: 'Mostrar senha' }));
    expect(input).toHaveAttribute('type', 'text');

    await user.click(screen.getByRole('button', { name: 'Ocultar senha' }));
    expect(input).toHaveAttribute('type', 'password');
  });

  it('links the error and the help text to the input for screen readers', () => {
    render(<TextField id="e" label="Email" value="" onChange={() => {}} error="Informe um email válido." help="Ajuda" />);
    const input = screen.getByLabelText('Email');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Informe um email válido. Ajuda');
  });
});
```

- [ ] **Step 5: Run the tests and confirm they fail.**
Run: `npm test`
Expected: FAIL. `AuthProvider`, `App`'s `AppRoutes` and `Field` do not exist. `HealthStatus.test.tsx` should already pass.

- [ ] **Step 6: Implement the provider** `web/src/auth/AuthProvider.tsx`:

```tsx
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { Navigate, useLocation, useNavigate, useSearchParams } from 'react-router';
import { setAuthRequiredHandler } from './apiFetch';
import { fetchMe, login, logout } from './authApi';
import { safeNext } from './safeNext';
import { endSession, onSessionEnd, refreshSession, startSession } from './session';
import type { Account } from './types';

export type AuthState = { status: 'loading' } | { status: 'guest' } | { status: 'signed-in'; account: Account };

type AuthContextValue = {
  state: AuthState;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => Promise<void>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<AuthState>({ status: 'loading' });
  const navigate = useNavigate();
  const location = useLocation();
  const here = useRef(location);

  useEffect(() => {
    here.current = location;
  }, [location]);

  // Page load: the cookie (if any) buys a fresh access token. StrictMode's second run joins the same refresh.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      const outcome = await refreshSession();
      if (cancelled) {
        return;
      }
      if (outcome !== 'refreshed') {
        setState({ status: 'guest' });
        return;
      }
      try {
        const account = await fetchMe();
        if (!cancelled) {
          setState({ status: 'signed-in', account });
        }
      } catch {
        if (!cancelled) {
          endSession();
          setState({ status: 'guest' });
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // The refresh cookie was refused mid-use (expired, revoked, reused): back to Guest.
  useEffect(() => onSessionEnd(() => setState({ status: 'guest' })), []);

  // The Guest boundary: a write that ends in 401 goes to sign-up and remembers where it came from.
  useEffect(
    () =>
      setAuthRequiredHandler(() => {
        const { pathname, search } = here.current;
        navigate(`/cadastro?next=${encodeURIComponent(pathname + search)}`);
      }),
    [navigate],
  );

  const signIn = useCallback(async (email: string, password: string) => {
    startSession(await login(email, password));
    try {
      setState({ status: 'signed-in', account: await fetchMe() });
    } catch (error) {
      endSession();
      throw error;
    }
  }, []);

  const signOut = useCallback(async () => {
    try {
      await logout();
    } catch {
      // The local session ends anyway; the cookie expires on its own.
    }
    endSession();
    setState({ status: 'guest' });
    navigate('/');
  }, [navigate]);

  const value = useMemo(() => ({ state, signIn, signOut }), [state, signIn, signOut]);
  return <AuthContext value={value}>{children}</AuthContext>;
}

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext);
  if (!value) {
    throw new Error('useAuth must be used inside <AuthProvider>');
  }
  return value;
}

/** Login, sign-up and "check your email" are for Guests; a signed-in person goes on to ?next (or home). */
export function GuestOnly({ children }: { children: ReactNode }) {
  const { state } = useAuth();
  const [params] = useSearchParams();
  if (state.status === 'loading') {
    return null;
  }
  if (state.status === 'signed-in') {
    return <Navigate to={safeNext(params.get('next'))} replace />;
  }
  return children;
}
```

- [ ] **Step 7: Implement the routes and pages.** `web/src/App.tsx`:

```tsx
import type { ReactNode } from 'react';
import { BrowserRouter, Route, Routes } from 'react-router';
import { AuthProvider, GuestOnly } from './auth/AuthProvider';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { RegisterPage } from './pages/RegisterPage';
import { VerifyEmailSentPage } from './pages/VerifyEmailSentPage';

/** `children`: extra <Route>s (tests use this to add a page that does a write). */
export function AppRoutes({ children }: { children?: ReactNode }) {
  return (
    <Routes>
      <Route path="/" element={<HomePage />} />
      <Route path="/entrar" element={<GuestOnly><LoginPage /></GuestOnly>} />
      <Route path="/cadastro" element={<GuestOnly><RegisterPage /></GuestOnly>} />
      <Route path="/verifique-email" element={<GuestOnly><VerifyEmailSentPage /></GuestOnly>} />
      {children}
    </Routes>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  );
}
```

`web/src/pages/HomePage.tsx`. This is a placeholder until the feed and top bar exist; logout lives here for now:

```tsx
import { Link } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { HealthStatus } from '../HealthStatus';

export function HomePage() {
  const { state, signOut } = useAuth();
  return (
    <main className="home">
      <h1>Musicboxd</h1>
      {state.status === 'guest' && (
        <nav aria-label="Conta" className="home-account">
          <Link to="/entrar">Entrar</Link>
          <Link to="/cadastro">Criar conta</Link>
        </nav>
      )}
      {state.status === 'signed-in' && (
        <p className="home-account">
          Conectado como @{state.account.username}{' '}
          <button type="button" className="btn btn--secondary" onClick={() => void signOut()}>
            Sair
          </button>
        </p>
      )}
      <HealthStatus />
    </main>
  );
}
```

Stub pages. Each is replaced in its own task (6, 7, 8). They show the decoded URL so the Task 5 tests can assert on navigation.

`web/src/pages/LoginPage.tsx`:

```tsx
import { useLocation } from 'react-router';

export function LoginPage() {
  const { pathname, search } = useLocation();
  return <p data-testid="page">{decodeURIComponent(pathname + search)}</p>;
}
```

`web/src/pages/RegisterPage.tsx`:

```tsx
import { useLocation } from 'react-router';

export function RegisterPage() {
  const { pathname, search } = useLocation();
  return <p data-testid="page">{decodeURIComponent(pathname + search)}</p>;
}
```

`web/src/pages/VerifyEmailSentPage.tsx`:

```tsx
import { useLocation } from 'react-router';

export function VerifyEmailSentPage() {
  const { pathname, search } = useLocation();
  return <p data-testid="page">{decodeURIComponent(pathname + search)}</p>;
}
```

`web/src/main.tsx`: add `import './styles/tokens.css';` and `import './styles/auth.css';` above the `App` import.

- [ ] **Step 8: Implement the UI primitives.** `web/src/ui/icons.tsx`:

```tsx
const base = { width: 20, height: 20, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true } as const;

export function EyeIcon() {
  return (
    <svg {...base}>
      <path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}

export function EyeOffIcon() {
  return (
    <svg {...base}>
      <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
      <line x1="1" y1="1" x2="23" y2="23" />
    </svg>
  );
}

export function MailIcon() {
  return (
    <svg {...base} width={26} height={26}>
      <path d="M4 4h16c1.1 0 2 .9 2 2v12c0 1.1-.9 2-2 2H4c-1.1 0-2-.9-2-2V6c0-1.1.9-2 2-2z" />
      <polyline points="22,6 12,13 2,6" />
    </svg>
  );
}
```

`web/src/ui/Field.tsx`:

```tsx
import { useState, type ReactNode } from 'react';
import { EyeIcon, EyeOffIcon } from './icons';

export type TextFieldProps = {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  type?: 'text' | 'email' | 'password';
  placeholder?: string;
  autoComplete?: string;
  /** Shown inside the input on the left, e.g. "@" for the username. */
  prefix?: string;
  help?: string;
  error?: ReactNode;
  /** Red border without a message of its own (login's shared "wrong credentials" alert). */
  invalid?: boolean;
  trailing?: ReactNode;
};

export function TextField({ id, label, value, onChange, type = 'text', placeholder, autoComplete, prefix, help, error, invalid, trailing }: TextFieldProps) {
  const errorId = error ? `${id}-error` : undefined;
  const helpId = help ? `${id}-help` : undefined;
  const describedBy = [errorId, helpId].filter(Boolean).join(' ') || undefined;
  const bad = Boolean(error) || Boolean(invalid);
  const input = (
    <input
      id={id}
      type={type}
      value={value}
      placeholder={placeholder}
      autoComplete={autoComplete}
      className={bad ? 'is-bad' : undefined}
      aria-invalid={bad || undefined}
      aria-describedby={describedBy}
      onChange={(event) => onChange(event.target.value)}
    />
  );
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {prefix || trailing ? (
        <div className={`field-control${prefix ? ' field-control--prefix' : ''}${trailing ? ' field-control--trailing' : ''}`}>
          {prefix && <span aria-hidden="true">{prefix}</span>}
          {input}
          {trailing}
        </div>
      ) : (
        input
      )}
      {error && <p className="field-error" id={errorId}>{error}</p>}
      {help && <p className="field-help" id={helpId}>{help}</p>}
    </div>
  );
}

export function PasswordField(props: Omit<TextFieldProps, 'type' | 'prefix' | 'trailing'>) {
  const [visible, setVisible] = useState(false);
  return (
    <TextField
      {...props}
      type={visible ? 'text' : 'password'}
      trailing={
        <button
          type="button"
          className="field-toggle"
          aria-label={visible ? 'Ocultar senha' : 'Mostrar senha'}
          onClick={() => setVisible((shown) => !shown)}
        >
          {visible ? <EyeOffIcon /> : <EyeIcon />}
        </button>
      }
    />
  );
}
```

`web/src/ui/Alert.tsx`:

```tsx
import type { ReactNode } from 'react';

export function Alert({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="alert" role="alert">
      <b>{title}</b> {children}
    </div>
  );
}
```

`web/src/ui/AuthLayout.tsx`:

```tsx
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import logo from '../assets/musicboxd-logo-dark.svg';

/** Auth card (EXPERIENCE.md card-auth): large logo above, no top bar. */
export function AuthLayout({ titleId, centered = false, children }: { titleId: string; centered?: boolean; children: ReactNode }) {
  return (
    <div className="auth-page">
      <div className="auth-brand">
        <Link className="auth-logo" to="/" aria-label="musicboxd - ir para o feed">
          <img src={logo} alt="" />
        </Link>
      </div>
      <main className="auth-main">
        <section className={centered ? 'auth-card auth-card--center' : 'auth-card'} aria-labelledby={titleId}>
          {children}
        </section>
      </main>
    </div>
  );
}
```

`web/src/vite-env.d.ts`:

```ts
/// <reference types="vite/client" />
```

Copy the logo: `cp musicboxd-brand/musicboxd-logo-dark.svg web/src/assets/musicboxd-logo-dark.svg`. The dark variant is for the dark theme; the mockup's light theme uses the `-light` one.

- [ ] **Step 9: Styles, fonts and the dev proxy.** `web/src/styles/tokens.css` (values from DESIGN.md):

```css
:root {
  --coral: #ff5a3c;
  --on-coral: #111317;
  --bg: #111317;
  --fg: #f3efe8;
  --muted: rgba(243, 239, 232, 0.62);
  --line: rgba(243, 239, 232, 0.14);
  --card: #1a1d22;
  --err: #ff8a73;
  --err-bg: rgba(255, 90, 60, 0.12);
  --font-display: Outfit, system-ui, sans-serif;
  --font-body: Inter, system-ui, -apple-system, 'Segoe UI', sans-serif;
  color-scheme: dark;
}

*,
*::before,
*::after {
  box-sizing: border-box;
}

body {
  margin: 0;
  background: var(--bg);
  color: var(--fg);
  font-family: var(--font-body);
  line-height: 1.5;
}

.home {
  max-width: 720px;
  margin: 0 auto;
  padding: 32px 16px;
}

.home-account {
  display: flex;
  gap: 16px;
  align-items: center;
}

.home a {
  color: var(--fg);
  font-weight: 600;
}
```

`web/src/styles/auth.css` (ported from the mockup, using the tokens):

```css
.auth-page h1,
.btn {
  font-family: var(--font-display);
}

.auth-brand {
  display: flex;
  justify-content: center;
  padding: 40px 32px 0;
}

.auth-logo {
  display: block;
  width: 260px;
  max-width: 70%;
}

.auth-logo img {
  display: block;
  width: 100%;
  height: auto;
}

.auth-main {
  display: flex;
  justify-content: center;
  padding: 28px 32px 48px;
}

.auth-card {
  width: 100%;
  max-width: 420px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 22px 24px;
}

.auth-card--center {
  text-align: center;
}

.auth-card h1 {
  margin: 0;
  font-weight: 700;
  font-size: 24px;
  line-height: 1.2;
}

.auth-lead {
  margin: 4px 0 0;
  color: var(--muted);
  font-size: 14px;
}

.auth-form {
  margin-top: 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  text-align: left;
}

.field label {
  font-size: 14px;
  font-weight: 600;
}

.field input {
  width: 100%;
  background: transparent;
  color: var(--fg);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 11px 14px;
  font: inherit;
  font-size: 15px;
}

.field input::placeholder {
  color: var(--muted);
}

.field input:focus-visible {
  outline: 2px solid var(--coral);
  outline-offset: 1px;
  border-color: var(--coral);
}

.field input.is-bad {
  border-color: var(--err);
}

.field-control {
  position: relative;
}

.field-control--prefix span {
  position: absolute;
  left: 14px;
  top: 50%;
  transform: translateY(-50%);
  color: var(--muted);
  font-size: 15px;
}

.field-control--prefix input {
  padding-left: 32px;
}

.field-control--trailing input {
  padding-right: 48px;
}

.field-toggle {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: none;
  border: 0;
  border-radius: 6px;
  color: var(--muted);
  cursor: pointer;
}

.field-toggle:hover {
  color: var(--fg);
}

.field-help,
.field-error {
  margin: 0;
  font-size: 13px;
}

.field-help {
  color: var(--muted);
}

.field-error {
  color: var(--err);
  font-weight: 500;
}

.field-error a {
  color: var(--err);
}

.alert {
  margin-top: 14px;
  background: var(--err-bg);
  border: 1px solid var(--err);
  border-radius: 8px;
  padding: 12px 14px;
  font-size: 14px;
  text-align: left;
}

.alert b {
  color: var(--err);
}

.auth-ok {
  margin: 16px 0 0;
  font-size: 14px;
  background: var(--err-bg);
  border-radius: 8px;
  padding: 10px 12px;
}

.auth-mail {
  width: 56px;
  height: 56px;
  border-radius: 50%;
  border: 1.5px solid var(--coral);
  color: var(--coral);
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 14px;
}

.btn {
  background: var(--coral);
  color: var(--on-coral);
  border: 0;
  border-radius: 999px;
  padding: 12px 22px;
  font-weight: 700;
  font-size: 16px;
  cursor: pointer;
}

.btn--secondary {
  background: transparent;
  color: var(--fg);
  border: 1.5px solid var(--fg);
}

.btn--full {
  width: 100%;
  margin-top: 6px;
}

.auth-card--center .btn--full {
  margin-top: 16px;
}

.btn:disabled,
.field-toggle:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.btn:focus-visible,
.field-toggle:focus-visible,
.auth-page a:focus-visible {
  outline: 2px solid var(--coral);
  outline-offset: 3px;
}

.auth-alt {
  margin: 14px 0 0;
  text-align: center;
  color: var(--muted);
  font-size: 14px;
}

.auth-card a {
  color: var(--fg);
  font-weight: 600;
  text-underline-offset: 3px;
}

.auth-alt a:hover {
  color: var(--coral);
}

@media (max-width: 480px) {
  .auth-brand {
    padding: 32px 16px 0;
  }

  .auth-logo {
    width: 200px;
  }

  .auth-main {
    padding: 20px 16px 32px;
  }

  .auth-card {
    padding: 20px 18px;
  }

  .auth-card h1 {
    font-size: 22px;
  }
}
```

`web/index.html`: set `<html lang="pt-BR">` and add, inside `<head>`:

```html
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600&family=Outfit:wght@700&display=swap" />
```

`web/vite.config.ts`: add the dev proxy, so `/api` (and the refresh cookie) stays same-origin under `npm run dev`:

```ts
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
```

- [ ] **Step 10: Run all tests and the type check.**
Run: `npm test && npx tsc -b`
Expected: PASS. No type errors.

- [ ] **Step 11: Commit.**

```bash
git add web/package.json web/package-lock.json web/vite.config.ts web/index.html web/src
git commit -m "Add routing, the session provider with logout and the Guest boundary, and the auth UI kit (MBD-22)"
```

---

### Task 6: Login screen (Entrar)

**Files:**
- Modify: `web/src/pages/LoginPage.tsx` (replace the stub)
- Test: `web/src/pages/LoginPage.test.tsx`

**Interfaces:**
- Consumes: `useAuth().signIn`, `ApiError`, `PROBLEM_TYPES`, `safeNext`, `AuthLayout`, `TextField`, `PasswordField`, `Alert`.
- Produces: on 403 email-not-verified it navigates to `/verifique-email` with `state: { email }`. Task 8 reads `location.state.email`.

- [ ] **Step 1: Write the failing tests** `web/src/pages/LoginPage.test.tsx`:

```tsx
import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route } from 'react-router';
import { fakeApi, json, problem, tokenResponse } from '../test/fakeApi';
import { LocationProbe, renderApp } from '../test/renderApp';

const ANA = { id: '1', email: 'ana@exemplo.com', username: 'ana' };

async function fillAndSubmit(email: string, password: string) {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText('Email'), email);
  await user.type(screen.getByLabelText('Senha'), password);
  await user.click(screen.getByRole('button', { name: 'Entrar' }));
}

describe('LoginPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('logs in and returns to ?next, keeping the session in memory without another refresh', async () => {
    const { calls } = fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/login': () => tokenResponse('a1'),
      'GET /api/v1/accounts/me': () => json(200, ANA),
    });
    renderApp('/entrar?next=%2Falbum%2F42', <Route path="/album/:id" element={<LocationProbe />} />);

    await fillAndSubmit('ana@exemplo.com', 'correct-horse');

    expect(await screen.findByTestId('location')).toHaveTextContent('/album/42');
    expect(calls.filter((c) => c.url === '/api/v1/auth/refresh')).toHaveLength(1); // the page-load one only
    expect(JSON.parse(calls.find((c) => c.url === '/api/v1/auth/login')!.init.body as string)).toEqual({
      email: 'ana@exemplo.com',
      password: 'correct-horse',
    });
  });

  it('shows the credentials alert and marks both fields on a 401', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401), 'POST /api/v1/auth/login': () => problem(401) });
    renderApp('/entrar');

    await fillAndSubmit('ana@exemplo.com', 'wrong-password');

    expect(await screen.findByRole('alert')).toHaveTextContent('Email ou senha incorretos. Confira os dados e tente de novo.');
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText('Senha')).toHaveAttribute('aria-invalid', 'true');
  });

  it('sends an unverified account to "Verifique seu email" with its address', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/login': () => problem(403, 'urn:musicboxd:problem:email-not-verified'),
    });
    renderApp('/entrar');

    await fillAndSubmit('ana@exemplo.com', 'correct-horse');

    expect(await screen.findByTestId('page')).toHaveTextContent('/verifique-email');
  });

  it('asks for the missing fields without calling the API', async () => {
    const { calls } = fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    const user = userEvent.setup();
    renderApp('/entrar');

    await user.click(await screen.findByRole('button', { name: 'Entrar' }));

    expect(screen.getByText('Informe seu email.')).toBeInTheDocument();
    expect(screen.getByText('Informe sua senha.')).toBeInTheDocument();
    expect(calls.some((c) => c.url === '/api/v1/auth/login')).toBe(false);
  });

  it('shows a retry message when the API cannot be reached', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/login': () => Promise.reject(new TypeError('Failed to fetch')),
    });
    renderApp('/entrar');

    await fillAndSubmit('ana@exemplo.com', 'correct-horse');

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível entrar agora.');
  });

  it('links to sign-up, keeping ?next', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    renderApp('/entrar?next=%2Falbum%2F42');

    expect(await screen.findByRole('link', { name: 'Criar conta' })).toHaveAttribute('href', '/cadastro?next=%2Falbum%2F42');
  });
});
```

(The 403 test still sees the Task 5 stub for `/verifique-email`, which renders `data-testid="page"`. Task 8 replaces that assertion with the real heading.)

- [ ] **Step 2: Run the tests and confirm they fail.**
Run: `npm test -- src/pages/LoginPage.test.tsx`
Expected: FAIL. No `Email` label (the stub renders only a `<p>`).

- [ ] **Step 3: Implement** `web/src/pages/LoginPage.tsx`:

```tsx
import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useAuth } from '../auth/AuthProvider';
import { ApiError, PROBLEM_TYPES } from '../auth/authApi';
import { safeNext } from '../auth/safeNext';
import { Alert } from '../ui/Alert';
import { AuthLayout } from '../ui/AuthLayout';
import { PasswordField, TextField } from '../ui/Field';

type Failure = 'credentials' | 'unavailable' | null;

export function LoginPage() {
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [missing, setMissing] = useState({ email: false, password: false });
  const [failure, setFailure] = useState<Failure>(null);
  const [submitting, setSubmitting] = useState(false);
  const query = params.toString() ? `?${params.toString()}` : '';

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const nowMissing = { email: email.trim() === '', password: password === '' };
    setMissing(nowMissing);
    setFailure(null);
    if (nowMissing.email || nowMissing.password) {
      return;
    }
    setSubmitting(true);
    try {
      await signIn(email, password);
      navigate(safeNext(params.get('next')), { replace: true });
    } catch (error) {
      if (error instanceof ApiError && error.type === PROBLEM_TYPES.emailNotVerified) {
        navigate('/verifique-email', { state: { email: email.trim() } });
        return;
      }
      setFailure(error instanceof ApiError && error.status === 401 ? 'credentials' : 'unavailable');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout titleId="login-title">
      <h1 id="login-title">Entrar</h1>
      <p className="auth-lead">Bem-vindo de volta ao musicboxd.</p>
      {failure === 'credentials' && <Alert title="Email ou senha incorretos.">Confira os dados e tente de novo.</Alert>}
      {failure === 'unavailable' && <Alert title="Não foi possível entrar agora.">Tente de novo em instantes.</Alert>}
      <form className="auth-form" noValidate onSubmit={(event) => void handleSubmit(event)}>
        <TextField
          id="login-email"
          label="Email"
          type="email"
          autoComplete="email"
          placeholder="voce@exemplo.com"
          value={email}
          onChange={setEmail}
          invalid={failure === 'credentials'}
          error={missing.email ? 'Informe seu email.' : undefined}
        />
        <PasswordField
          id="login-password"
          label="Senha"
          autoComplete="current-password"
          value={password}
          onChange={setPassword}
          invalid={failure === 'credentials'}
          error={missing.password ? 'Informe sua senha.' : undefined}
        />
        <button className="btn btn--full" type="submit" disabled={submitting}>
          Entrar
        </button>
      </form>
      <p className="auth-alt">
        Novo por aqui? <Link to={`/cadastro${query}`}>Criar conta</Link>
      </p>
    </AuthLayout>
  );
}
```

New copy `[ASSUMPTION]`: "Informe seu email.", "Informe sua senha.", "Não foi possível entrar agora. Tente de novo em instantes."

- [ ] **Step 4: Run all tests and confirm they pass.**
Run: `npm test`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add web/src/pages/LoginPage.tsx web/src/pages/LoginPage.test.tsx
git commit -m "Add the Entrar screen with credential, unverified and offline states (MBD-22)"
```

---

### Task 7: Sign-up screen (Criar conta)

**Files:**
- Modify: `web/src/pages/RegisterPage.tsx` (replace the stub)
- Modify: `web/src/auth/AuthProvider.test.tsx` (the Guest-boundary assertion now checks the real page)
- Test: `web/src/pages/RegisterPage.test.tsx`

**Interfaces:**
- Consumes: `register`, `ApiError`, `PROBLEM_TYPES`, UI primitives. It reads `location.state` `{ email?, username? }` (sent by Task 8's "Voltar e corrigir").
- Produces: on 201 it navigates to `/verifique-email` with `state: { email, username }`. It exports `validateRegistration(input): FieldErrors`.

- [ ] **Step 1: Write the failing tests** `web/src/pages/RegisterPage.test.tsx`:

```tsx
import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { fakeApi, json, problem } from '../test/fakeApi';
import { renderApp } from '../test/renderApp';
import { validateRegistration } from './RegisterPage';

async function fill(username: string, email: string, password: string) {
  const user = userEvent.setup();
  if (username) await user.type(await screen.findByLabelText('Nome de usuário'), username);
  if (email) await user.type(screen.getByLabelText('Email'), email);
  if (password) await user.type(screen.getByLabelText('Senha'), password);
  await user.click(screen.getByRole('button', { name: 'Criar conta' }));
}

describe('validateRegistration', () => {
  it('mirrors the API rules', () => {
    expect(validateRegistration({ username: 'ana_silva', email: 'ana@exemplo.com', password: '12345678' })).toEqual({});
    expect(validateRegistration({ username: 'an', email: 'ana', password: '1234567' })).toEqual({
      username: 'Use de 3 a 20 letras, números ou sublinhado.',
      email: 'Informe um email válido.',
      password: 'A senha precisa ter pelo menos 8 caracteres.',
    });
    expect(validateRegistration({ username: 'ana.silva', email: 'a@b', password: '12345678' }).username).toBeDefined();
    expect(validateRegistration({ username: 'a'.repeat(21), email: 'a@b', password: '12345678' }).username).toBeDefined();
  });
});

describe('RegisterPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('creates the account and moves to "Verifique seu email"', async () => {
    const { calls } = fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/register': () => json(201, { id: '1', email: 'ana@exemplo.com', username: 'ana_silva' }),
    });
    renderApp('/cadastro');

    await fill('@ana_silva', ' ana@exemplo.com ', 'correct-horse');

    expect(await screen.findByTestId('page')).toHaveTextContent('/verifique-email');
    expect(JSON.parse(calls.find((c) => c.url === '/api/v1/auth/register')!.init.body as string)).toEqual({
      username: 'ana_silva', // a typed leading "@" is dropped
      email: 'ana@exemplo.com',
      password: 'correct-horse',
    });
  });

  it('shows field errors and the top alert without calling the API', async () => {
    const { calls } = fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    renderApp('/cadastro');

    await fill('ana', 'ana@exemplo.com', 'abc123');

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível criar a conta. Corrija os campos destacados.');
    expect(screen.getByLabelText('Senha')).toHaveAccessibleDescription(
      'A senha precisa ter pelo menos 8 caracteres. Mínimo de 8 caracteres.',
    );
    expect(calls.some((c) => c.url === '/api/v1/auth/register')).toBe(false);
  });

  it('puts "email already in use" under the email field, with a link to log in', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/register': () => problem(409, 'urn:musicboxd:problem:email-taken'),
    });
    renderApp('/cadastro');

    await fill('ana_silva', 'ana@exemplo.com', 'correct-horse');

    expect(await screen.findByText(/Este email já está em uso\./)).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toHaveAttribute('aria-invalid', 'true');
    // One "Entrar" in the field error, one in the footer.
    expect(screen.getAllByRole('link', { name: 'Entrar' })).toHaveLength(2);
  });

  it('puts "username taken" under the username field', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/register': () => problem(409, 'urn:musicboxd:problem:username-taken'),
    });
    renderApp('/cadastro');

    await fill('ana_silva', 'ana@exemplo.com', 'correct-horse');

    expect(await screen.findByText('Este nome de usuário já está em uso.')).toBeInTheDocument();
    expect(screen.getByLabelText('Nome de usuário')).toHaveAttribute('aria-invalid', 'true');
  });

  it('prefills username and email when coming back to fix them', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    renderApp({ pathname: '/cadastro', state: { email: 'ana@exemplo.com', username: 'ana_silva' } });

    expect(await screen.findByLabelText('Nome de usuário')).toHaveValue('ana_silva');
    expect(screen.getByLabelText('Email')).toHaveValue('ana@exemplo.com');
    expect(screen.getByLabelText('Senha')).toHaveValue('');
  });
});
```

In `web/src/auth/AuthProvider.test.tsx`, change the Guest-boundary assertion to the real page:

```tsx
    expect(await screen.findByRole('heading', { name: 'Criar conta' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Entrar' })).toHaveAttribute('href', '/entrar?next=%2Falbum%2F42');
```

- [ ] **Step 2: Run the tests and confirm they fail.**
Run: `npm test -- src/pages/RegisterPage.test.tsx src/auth/AuthProvider.test.tsx`
Expected: FAIL. `validateRegistration` is not exported and there is no `Nome de usuário` label.

- [ ] **Step 3: Implement** `web/src/pages/RegisterPage.tsx`:

```tsx
import { useState, type FormEvent, type ReactNode } from 'react';
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router';
import { ApiError, PROBLEM_TYPES, register } from '../auth/authApi';
import { Alert } from '../ui/Alert';
import { AuthLayout } from '../ui/AuthLayout';
import { PasswordField, TextField } from '../ui/Field';

type Input = { username: string; email: string; password: string };
type FieldErrors = Partial<Record<keyof Input, ReactNode>>;
type Failure = 'rejected' | 'unavailable' | null;

// Same rules as AuthController.RegisterRequest; the server stays the authority (AD-7).
const USERNAME = /^[A-Za-z0-9_]{3,20}$/;
const EMAIL = /^[^\s@]+@[^\s@]+$/;
const MIN_PASSWORD = 8;

export function validateRegistration(input: Input): FieldErrors {
  const errors: FieldErrors = {};
  if (!USERNAME.test(input.username)) {
    errors.username = 'Use de 3 a 20 letras, números ou sublinhado.';
  }
  if (!EMAIL.test(input.email)) {
    errors.email = 'Informe um email válido.';
  }
  if (input.password.length < MIN_PASSWORD) {
    errors.password = 'A senha precisa ter pelo menos 8 caracteres.';
  }
  return errors;
}

export function RegisterPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [params] = useSearchParams();
  const prefill = (location.state ?? {}) as { email?: string; username?: string };
  const [username, setUsername] = useState(prefill.username ?? '');
  const [email, setEmail] = useState(prefill.email ?? '');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState<FieldErrors>({});
  const [failure, setFailure] = useState<Failure>(null);
  const [submitting, setSubmitting] = useState(false);
  const query = params.toString() ? `?${params.toString()}` : '';

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const input = { username: username.trim().replace(/^@/, ''), email: email.trim(), password };
    const found = validateRegistration(input);
    setErrors(found);
    setFailure(null);
    if (Object.keys(found).length > 0) {
      return;
    }
    setSubmitting(true);
    try {
      await register(input);
      navigate('/verifique-email', { state: { email: input.email, username: input.username } });
    } catch (error) {
      if (error instanceof ApiError && error.type === PROBLEM_TYPES.emailTaken) {
        setErrors({ email: <>Este email já está em uso. <Link to={`/entrar${query}`}>Entrar</Link></> });
      } else if (error instanceof ApiError && error.type === PROBLEM_TYPES.usernameTaken) {
        setErrors({ username: 'Este nome de usuário já está em uso.' });
      } else {
        setFailure(error instanceof ApiError && error.status === 400 ? 'rejected' : 'unavailable');
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <AuthLayout titleId="register-title">
      <h1 id="register-title">Criar conta</h1>
      <p className="auth-lead">Só precisa de um nome de usuário, email e senha.</p>
      {Object.keys(errors).length > 0 && <Alert title="Não foi possível criar a conta.">Corrija os campos destacados.</Alert>}
      {failure === 'rejected' && <Alert title="Não foi possível criar a conta.">Confira os dados e tente de novo.</Alert>}
      {failure === 'unavailable' && <Alert title="Não foi possível criar a conta agora.">Tente de novo em instantes.</Alert>}
      <form className="auth-form" noValidate onSubmit={(event) => void handleSubmit(event)}>
        <TextField
          id="register-username"
          label="Nome de usuário"
          prefix="@"
          autoComplete="username"
          placeholder="seunome"
          value={username}
          onChange={setUsername}
          error={errors.username}
          help="Aparece no seu perfil. De 3 a 20 letras, números ou sublinhado."
        />
        <TextField
          id="register-email"
          label="Email"
          type="email"
          autoComplete="email"
          placeholder="voce@exemplo.com"
          value={email}
          onChange={setEmail}
          error={errors.email}
        />
        <PasswordField
          id="register-password"
          label="Senha"
          autoComplete="new-password"
          value={password}
          onChange={setPassword}
          error={errors.password}
          help="Mínimo de 8 caracteres."
        />
        <button className="btn btn--full" type="submit" disabled={submitting}>
          Criar conta
        </button>
      </form>
      <p className="auth-alt">
        Já tem conta? <Link to={`/entrar${query}`}>Entrar</Link>
      </p>
    </AuthLayout>
  );
}
```

New copy `[ASSUMPTION]`: username rule and help text (the API rule wins over the mockup's "ponto"), "Informe um email válido.", "Este nome de usuário já está em uso.", and the two generic alerts.

- [ ] **Step 4: Run all tests and confirm they pass.**
Run: `npm test`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add web/src/pages/RegisterPage.tsx web/src/pages/RegisterPage.test.tsx web/src/auth/AuthProvider.test.tsx
git commit -m "Add the Criar conta screen with client checks and per-field 409 errors (MBD-22)"
```

---

### Task 8: "Verifique seu email" screen with resend

**Files:**
- Modify: `web/src/pages/VerifyEmailSentPage.tsx` (replace the stub)
- Modify: `web/src/pages/LoginPage.test.tsx`, `web/src/pages/RegisterPage.test.tsx` (the stub `page` assertions now check the real heading)
- Test: `web/src/pages/VerifyEmailSentPage.test.tsx`

**Interfaces:**
- Consumes: `resendVerificationEmail`, `ApiError`, `MailIcon`, `AuthLayout`, `Alert`. It reads `location.state` `{ email?, username? }` (from Tasks 6 and 7).
- Produces: "Voltar e corrigir" links to `/cadastro` with `state: { email, username }`.

- [ ] **Step 1: Write the failing tests** `web/src/pages/VerifyEmailSentPage.test.tsx`:

```tsx
import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { fakeApi, problem } from '../test/fakeApi';
import { renderApp } from '../test/renderApp';

const AT_VERIFY = { pathname: '/verifique-email', state: { email: 'ana@exemplo.com', username: 'ana_silva' } };

describe('VerifyEmailSentPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('names the address the link went to', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    renderApp(AT_VERIFY);

    expect(await screen.findByRole('heading', { name: 'Verifique seu email' })).toBeInTheDocument();
    expect(screen.getByText('ana@exemplo.com')).toBeInTheDocument();
  });

  it('resends the email and confirms it', async () => {
    const { calls } = fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/verification-email': () => new Response(null, { status: 202 }),
    });
    const user = userEvent.setup();
    renderApp(AT_VERIFY);

    await user.click(await screen.findByRole('button', { name: 'Reenviar email' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Email reenviado. Confira também a caixa de spam.');
    expect(JSON.parse(calls.find((c) => c.url === '/api/v1/auth/verification-email')!.init.body as string)).toEqual({
      email: 'ana@exemplo.com',
    });
  });

  it('explains the wait when resending too often (429)', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => problem(401),
      'POST /api/v1/auth/verification-email': () => problem(429),
    });
    const user = userEvent.setup();
    renderApp(AT_VERIFY);

    await user.click(await screen.findByRole('button', { name: 'Reenviar email' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitos reenvios. Espere alguns minutos e tente de novo.');
  });

  it('without a known address (hard refresh) it hides the resend button', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    renderApp('/verifique-email');

    expect(await screen.findByText(/Enviamos um link de confirmação para o seu email\./)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Reenviar email' })).toBeNull();
  });

  it('"Voltar e corrigir" returns to sign-up with the fields filled in', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401) });
    const user = userEvent.setup();
    renderApp(AT_VERIFY);

    await user.click(await screen.findByRole('link', { name: 'Voltar e corrigir' }));

    expect(await screen.findByLabelText('Email')).toHaveValue('ana@exemplo.com');
    expect(screen.getByLabelText('Nome de usuário')).toHaveValue('ana_silva');
  });
});
```

In `LoginPage.test.tsx` (unverified test) and `RegisterPage.test.tsx` (success test), replace `expect(await screen.findByTestId('page')).toHaveTextContent('/verifique-email');` with:

```tsx
    expect(await screen.findByRole('heading', { name: 'Verifique seu email' })).toBeInTheDocument();
    expect(screen.getByText('ana@exemplo.com')).toBeInTheDocument();
```

- [ ] **Step 2: Run the tests and confirm they fail.**
Run: `npm test -- src/pages`
Expected: FAIL. There is no `Verifique seu email` heading.

- [ ] **Step 3: Implement** `web/src/pages/VerifyEmailSentPage.tsx`:

```tsx
import { useState } from 'react';
import { Link, useLocation } from 'react-router';
import { ApiError, resendVerificationEmail } from '../auth/authApi';
import { Alert } from '../ui/Alert';
import { AuthLayout } from '../ui/AuthLayout';
import { MailIcon } from '../ui/icons';

type Resend = 'idle' | 'sending' | 'sent' | 'rate-limited' | 'unavailable';

export function VerifyEmailSentPage() {
  const location = useLocation();
  // Router state only: the address is not put in the URL (it would land in logs and history).
  const { email, username } = (location.state ?? {}) as { email?: string; username?: string };
  const [resend, setResend] = useState<Resend>('idle');

  async function handleResend() {
    if (!email) {
      return;
    }
    setResend('sending');
    try {
      await resendVerificationEmail(email);
      setResend('sent');
    } catch (error) {
      setResend(error instanceof ApiError && error.status === 429 ? 'rate-limited' : 'unavailable');
    }
  }

  return (
    <AuthLayout titleId="verify-title" centered>
      <div className="auth-mail" aria-hidden="true">
        <MailIcon />
      </div>
      <h1 id="verify-title">Verifique seu email</h1>
      <p className="auth-lead">
        {email ? (
          <>
            Enviamos um link de confirmação para <b>{email}</b>.
          </>
        ) : (
          'Enviamos um link de confirmação para o seu email.'
        )}{' '}
        Abra o email e clique no link para ativar sua conta.
      </p>
      {resend === 'sent' && (
        <p className="auth-ok" role="status">
          Email reenviado. Confira também a caixa de spam.
        </p>
      )}
      {resend === 'rate-limited' && <Alert title="Muitos reenvios.">Espere alguns minutos e tente de novo.</Alert>}
      {resend === 'unavailable' && <Alert title="Não foi possível reenviar agora.">Tente de novo em instantes.</Alert>}
      {email && (
        <button className="btn btn--secondary btn--full" type="button" disabled={resend === 'sending'} onClick={() => void handleResend()}>
          Reenviar email
        </button>
      )}
      <p className="auth-alt">
        Não recebeu? Confira o spam ou reenvie.
        <br />
        Email errado?{' '}
        <Link to="/cadastro" state={{ email, username }}>
          Voltar e corrigir
        </Link>
      </p>
    </AuthLayout>
  );
}
```

New copy `[ASSUMPTION]`: the 429 alert, the unavailable alert, and the no-address variant of the lead.

- [ ] **Step 4: Run all tests and the type check.**
Run: `npm test && npx tsc -b`
Expected: PASS.

- [ ] **Step 5: Commit.**

```bash
git add web/src/pages
git commit -m "Add the Verifique seu email screen with resend and Voltar e corrigir (MBD-22)"
```

---

### Task 9: End-to-end check in a real browser

No new code unless this step finds a bug. This task proves the cookie, proxy and timer work outside jsdom.

- [ ] **Step 1: Start the stack.** From `api/`, start the API the way `docs/runbooks/runbook-mbd-17-auth.md` describes (the dev mail sender logs the verification link). Set `MUSICBOXD_PUBLIC_BASE_URL=http://localhost:5173` so the emailed link goes through the Vite proxy. To watch the refresh without waiting 15 minutes, also set `MUSICBOXD_AUTH_ACCESSTOKENTTL=2m` (Spring relaxed binding of `musicboxd.auth.access-token-ttl`: dots become underscores and dashes are dropped). From `web/`: `npm run dev`. Use Chrome or Firefox at `http://localhost:5173`.

- [ ] **Step 2: Walk the three screens.**
  1. `/cadastro`: submit `abc123` as the password. Expect the field error and the top alert. Fix it and submit. Expect "Verifique seu email" with your address.
  2. Click "Reenviar email". Expect the confirmation. Click it 3 more times. Expect the 429 alert (limit of 3 per 15 min).
  3. `/entrar` before verifying. Expect to be sent to "Verifique seu email".
  4. Open the verification link from the API log. Then log in. Expect the home page with "Conectado como @…".
  5. Wrong password: expect "Email ou senha incorretos."

- [ ] **Step 3: Watch the refresh (DevTools → Network).**
  1. With the 2-minute TTL, wait about 60 s after login. Expect one `POST /api/v1/auth/refresh` → 200 with a new `Set-Cookie` and no 401 in between.
  2. Reload the page (hard refresh). Expect one refresh → 200 and you stay signed in. In DevTools → Application, `localStorage` and `sessionStorage` stay empty.
  3. Offline check: DevTools → Network → Offline, wait past expiry, then go back online and reload. Expect to be signed in, not bounced to sign-up.

- [ ] **Step 4: Logout and the Guest boundary.**
  1. Click "Sair". Expect `DELETE /api/v1/auth/refresh` → 204 and the Entrar/Criar conta links. Reload: still a Guest.
  2. No write screen exists yet ("once one exists", AC). In the console, run `fetch('/api/v1/auth/refresh',{method:'POST'}).then(r=>r.status)` and expect `401`, which confirms the cookie is gone. The redirect itself is covered by `AuthProvider.test.tsx`.

- [ ] **Step 5: Production build.**
Run (from `web/`, with `api/build/openapi.json` present or via Docker): `npm run build`
Expected: the build succeeds. Optionally run `docker compose -f deploy/docker-compose.yml up --build` and repeat Step 2.4 through nginx at `http://localhost`.

- [ ] **Step 6: Commit any fixes** from this task with a message that says what broke, ending `(MBD-22)`.

---

## Known limitations (tell the reviewer, not in scope)

- **"Voltar e corrigir" after a wrong email:** the account with the wrong address already exists and holds the username. Re-registering with the same username gets "Este nome de usuário já está em uso." A fix (expiring or editing unverified accounts) is a backend story.
- **The verification link opens JSON** (`GET /api/v1/auth/verify`). A SPA landing page that calls it and then shows "Conta ativada → Entrar" is a follow-up. `AuthController.verify` already notes this.
- **`next` is lost across sign-up → email → login.** EXPERIENCE.md lists it as open item (h).
