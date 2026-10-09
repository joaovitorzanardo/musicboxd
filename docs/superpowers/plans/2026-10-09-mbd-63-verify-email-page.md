# MBD-63 The Verification Link Opens a Confirmation Screen in the App — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The verification email links to an SPA page, `/verificar-email?token=…`, instead of the API. The page sends the token to `GET /api/v1/auth/verify` and shows either "Tudo certo!" or "Link inválido ou expirado". Each outcome has a single **Entrar** button that goes to `/entrar`, and nothing redirects automatically.

**Architecture:** The backend changes only the path in the emailed link (`VerificationEmailListener`). The verify endpoint and its idempotent 200 for a reused link stay exactly as they are (MBD-18). The SPA gets `verifyEmail(token)` in `auth/authApi.ts` and a new `pages/VerifyEmailPage.tsx`, routed at `/verificar-email` **without** `GuestOnly`, so a visitor who is already signed in still sees the outcome. nginx (`try_files … /index.html`) and the Vite dev server already serve unknown paths with the SPA, so neither needs a change.

**Tech Stack:** React 19.3, react-router 7.18 (declarative), TypeScript, Vitest 5 + Testing Library + user-event. Backend: Java 25 / Spring Boot 4.1, JUnit 5 / Testcontainers.

**Spec:** Jira MBD-63 / `_bmad-output/initiative-musicboxd/epic-contas-acesso/story-the-verification-link-opens-a-confirmation-screen-in-the-app.md`. Screen: `_bmad-output/planning-artifacts/ux-designs/ux-teste-2026-09-26/mockups/email-verification.html` (desktop and mobile 390). Architecture: `ARCHITECTURE-SPINE.md` AD-8.

**Branch:** MBD-23 is merged (`origin/main` = `accb4a2`). Run `git switch main && git pull && git switch -c story/mbd-63-verify-email-page`. The working tree has unrelated edits under `.agents/`. They come along on the switch: **never stage them** (use explicit `git add <path>`, never `git add -A` or `git add .`). Commit messages end with `(MBD-63)` and the `Co-Authored-By` trailer. Run npm from `web/`. Run Gradle from `api/` (`./gradlew` in Git Bash, `.\gradlew.bat` in PowerShell). Docker must be running for Testcontainers.

## Decisions (defaults chosen for this story)

| Question | Decision |
|---|---|
| Route | `/verificar-email`, as the ticket says. It is a different route from the existing `/verifique-email` ("Verifique seu email", the screen shown after sign-up). |
| Guest-only? | No. The route is **not** wrapped in `GuestOnly` (ticket assumption: the link may open in a browser that already has a session). |
| Link already used | The API answers 200, so the page shows "Tudo certo!". The page has no separate "already used" copy. |
| Which errors mean "Link inválido ou expirado" | A **400** from the API (unknown, replaced, expired, empty or over-long token), and a missing or blank `token` in the URL. In that last case the page makes no API call. |
| Network failure, 5xx, nginx 429/503 | **[ASSUMPTION — confirm in review]** These do not say anything about the link, so calling it invalid would be wrong: the person would give up on a link that still works. The page shows an alert, "Não foi possível confirmar agora. Tente de novo em instantes.", with a **Tentar de novo** button. That state is not in the mockup. |
| While the request is in flight | **[ASSUMPTION]** The heading reads "Confirmando seu email…" (`role="status"`), with no icon and no button. The mockup does not show this state. |
| Focus | Each outcome heading has `tabIndex={-1}` (as in the mockup) and gets focus when the outcome appears, so a screen reader announces the result. |
| Entrar | A react-router `<Link to="/entrar">` styled as the primary full-width button. No email prefill: the page does not know the address. A visitor who is already signed in and clicks it is sent on to `/` by `GuestOnly` on `/entrar`, which is acceptable. |
| Strip the token from the URL afterwards? | No. Reloading must show the same outcome: a reload re-verifies and gets 200. The token was already single-use and was already in the old API URL, so this does not leak anything new. |
| React StrictMode runs the effect twice in dev | That sends two GETs. Both answer 200, because the endpoint takes a row lock and a consumed token returns 200. A `current` flag drops the result from the discarded first effect. There is no request de-duplication on purpose. |
| Old emails that point at `/api/v1/auth/verify` | They keep working as raw JSON. Nothing has launched, so there is no redirect shim (ticket risk note). |

## Global Constraints

- Link format: `<MUSICBOXD_PUBLIC_BASE_URL>/verificar-email?token=<raw token>` with no double slash when the base URL ends in `/`.
- Copy is pt-BR and comes verbatim from the mockup:
  - success: heading `Tudo certo!`, text `Seu email foi confirmado. Agora é só entrar e começar a avaliar seus álbuns.`
  - invalid: heading `Link inválido ou expirado`, text `Este link de confirmação não vale mais. Entre com seu email e senha: se a conta ainda não estiver ativa, mostramos como receber um novo link.`
  - button on both: `Entrar`
- Each outcome has exactly one button and no automatic redirect (user decision, 2026-10-09).
- The verify call is `anonymous` (no bearer token, no refresh). The endpoint is public.
- The verify endpoint, its 200/400 contract and its OpenAPI shape do not change. `npm run generate-client` must not produce a diff.
- `package.json`: no new dependencies.

## Review Focus

1. **A visitor who is already signed in opens the link.** The expected behavior is that they see "Tudo certo!" and are not sent to `/`. Pinned in Task 3 (`shows the outcome to a visitor who is already signed in`).
2. **The API is down or nginx throttles the request.** The page must **not** say the link is invalid. Pinned in Task 3 (`does not call a working link invalid when the API is unreachable`) and the retry test.
3. **The URL has no `token`, or `token=` is empty**, because the email client truncated the link. The expected behavior is "Link inválido ou expirado" with no request to the API. Pinned in Task 3 (`a link without a token is invalid without asking the API`).
4. **The token has characters that must be escaped** (a tampered link with `&`, `#`, `+` or spaces). It must reach the API as one encoded `token` value and must not split the query. Pinned in Task 2 (`encodes the token as a single query value`).
5. **The second open of the same link** (or the mail scanner opened it first). The page must still show "Tudo certo!". The backend already returns 200 for this (MBD-18 `EmailVerificationServiceTest`). On the SPA side this is the same 200 path, and the manual check in Task 4 confirms it end to end.

---

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `api/src/main/java/com/musicboxd/api/accounts/VerificationEmailListener.java` | Modify | The emailed link points at `/verificar-email`. |
| `api/src/main/java/com/musicboxd/api/accounts/AuthController.java:138` | Modify | Updates the Javadoc: the SPA page calls this endpoint now. |
| `api/src/test/java/com/musicboxd/api/mail/RecordingMailSender.java` | Modify | The link regex matches the new path. |
| `api/src/test/java/com/musicboxd/api/accounts/EmailVerificationServiceTest.java` | Modify | Asserts the new link format. |
| `docs/runbooks/runbook-mbd-18-email-verification.md:15` | Modify | Documents the new link. |
| `web/src/auth/authApi.ts` | Modify | Adds `verifyEmail(token)`. |
| `web/src/auth/authApi.test.ts` | Modify | Tests for `verifyEmail`. |
| `web/src/test/fakeApi.ts` | Modify | Route matching ignores the query string, so `GET /api/v1/auth/verify?token=…` can be stubbed. |
| `web/src/ui/icons.tsx` | Modify | Adds `CheckIcon` and `WarningIcon` (paths copied from the mockup). |
| `web/src/styles/auth.css` | Modify | Adds `.auth-mail--bad` and the `a.btn` link-as-button style. |
| `web/src/pages/VerifyEmailPage.tsx` | Create | The confirmation screen. |
| `web/src/pages/VerifyEmailPage.test.tsx` | Create | Screen behavior tests. |
| `web/src/App.tsx` | Modify | Adds the `/verificar-email` route (not guest-only). |

---

### Task 1: The emailed link points at the SPA page

**Files:**
- Modify: `api/src/main/java/com/musicboxd/api/accounts/VerificationEmailListener.java:35-40`
- Modify: `api/src/main/java/com/musicboxd/api/accounts/AuthController.java:138`
- Modify: `api/src/test/java/com/musicboxd/api/mail/RecordingMailSender.java:16`
- Modify: `api/src/test/java/com/musicboxd/api/accounts/EmailVerificationServiceTest.java:72,241`
- Modify: `docs/runbooks/runbook-mbd-18-email-verification.md:15`

**Interfaces:**
- Consumes: nothing new.
- Produces: emails whose link is `<base>/verificar-email?token=<raw>`. `RecordingMailSender.verificationLink(to)` and `RecordingMailSender.token(link)` keep their signatures, so `AuthFlowTest`, `AuthRateLimitTest` and the other suites that read the token from the email still work unchanged.

- [ ] **Step 1: Change the tests to expect the new link**

In `EmailVerificationServiceTest.java`, line 72:

```java
		assertThat(mail.verificationLink(email)).startsWith("http://localhost/verificar-email?token=");
```

and line 241 (`linkHasNoDoubleSlashWhenTheBaseUrlEndsWithOne`):

```java
		assertThat(mail.verificationLink(email)).isEqualTo("https://musicboxd.com.br/verificar-email?token=abc_DEF-123");
```

In `RecordingMailSender.java`, line 16:

```java
	private static final Pattern LINK = Pattern.compile("https?://\\S+/verificar-email\\?token=[A-Za-z0-9_-]+");
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests 'com.musicboxd.api.accounts.EmailVerificationServiceTest'`
Expected: FAIL. `registrationEmailsOneVerificationLinkToTheNormalizedAddress` and the other link readers fail with `AssertionError: no verification link in: …/api/v1/auth/verify?token=…`.

- [ ] **Step 3: Point the link at the SPA page**

In `VerificationEmailListener.send`:

```java
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void send(VerificationEmailRequested event) {
		// The SPA page (MBD-63) calls GET /api/v1/auth/verify and shows the outcome.
		URI link = UriComponentsBuilder.fromUri(props.linkBaseUrl())
			.path("/verificar-email")
			.queryParam("token", event.rawToken())
			.build()
			.toUri();
```

(Everything from `var message = new SimpleMailMessage();` on stays the same.)

In `AuthController.java`, replace the Javadoc on line 138:

```java
	/** Called by the SPA page the emailed link opens, /verificar-email (MBD-63). */
```

In `docs/runbooks/runbook-mbd-18-email-verification.md`, line 15:

```markdown
| Verification link | `https://musicboxd.com.br/verificar-email?token=...` (SPA page that calls `GET /api/v1/auth/verify`, MBD-63), valid 24 h, single use |
```

- [ ] **Step 4: Run the whole api suite**

Run: `./gradlew test`
Expected: PASS. The full suite runs because `AuthFlowTest`, `RefreshFlowTest`, `AuthRateLimitTest` and `StaffBootstrapTest` all read the token through `RecordingMailSender`. Also check that no other test still asserts the old path:

Run (from repo root): `grep -rn "api/v1/auth/verify?token" api/src`
Expected: no output.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/accounts/VerificationEmailListener.java \
        api/src/main/java/com/musicboxd/api/accounts/AuthController.java \
        api/src/test/java/com/musicboxd/api/mail/RecordingMailSender.java \
        api/src/test/java/com/musicboxd/api/accounts/EmailVerificationServiceTest.java \
        docs/runbooks/runbook-mbd-18-email-verification.md
git commit -m "Point the verification email at the SPA page /verificar-email (MBD-63)"
```

---

### Task 2: `verifyEmail(token)` in the SPA auth API

**Files:**
- Modify: `web/src/test/fakeApi.ts:16-20`
- Modify: `web/src/auth/authApi.ts`
- Test: `web/src/auth/authApi.test.ts`

**Interfaces:**
- Consumes: `apiFetch(path, { anonymous: true })`, `expectOk`, and `ApiError` (all already in `authApi.ts`/`apiFetch.ts`).
- Produces: `export async function verifyEmail(token: string): Promise<void>`. It resolves on 200. It throws `ApiError` with `status === 400` for an invalid or expired link, `ApiError` with another status for server or proxy errors, and `TypeError` from `fetch` when the network is down.
- Produces (test helper): `fakeApi` routes match on `METHOD /path` with the query string ignored. `Call.url` still holds the full path including the query.

- [ ] **Step 1: Let `fakeApi` match routes without the query string**

In `web/src/test/fakeApi.ts`, change the lookup inside the stub so a route keyed `GET /api/v1/auth/verify` answers `/api/v1/auth/verify?token=…`. Also update the doc comment:

```ts
/**
 * Stubs global fetch with a route table keyed "METHOD /path" (the query string is ignored for matching;
 * `call.url` keeps it). An array of handlers answers in order and repeats its last entry.
 * Unknown routes reject, so an unexpected call fails the test loudly.
 */
```

```ts
      const call = { method: (init.method ?? 'GET').toUpperCase(), url, init };
      calls.push(call);
      const queue = queues.get(`${call.method} ${url.split('?')[0]}`);
```

Run: `npm test`
Expected: PASS, with no change in the existing tests (none of their URLs has a query string).

- [ ] **Step 2: Write the failing tests**

Add `verifyEmail` to the import in `web/src/auth/authApi.test.ts`:

```ts
import { ApiError, PROBLEM_TYPES, login, register, verifyEmail } from './authApi';
```

and add these tests inside `describe('authApi', …)`:

```ts
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
```

- [ ] **Step 3: Run them to verify they fail**

Run: `npx vitest run src/auth/authApi.test.ts`
Expected: FAIL. `verifyEmail` is not exported (`SyntaxError`/`TypeError: verifyEmail is not a function`).

- [ ] **Step 4: Implement `verifyEmail`**

In `web/src/auth/authApi.ts`, after `resendVerificationEmail`:

```ts
/** The emailed link's token. 200 also when the link was already used; 400 when unknown, replaced or expired. */
export async function verifyEmail(token: string): Promise<void> {
  await expectOk(await apiFetch(`/api/v1/auth/verify?${new URLSearchParams({ token })}`, { anonymous: true }));
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `npm test`
Expected: PASS (all files).

- [ ] **Step 6: Commit**

```bash
git add web/src/test/fakeApi.ts web/src/auth/authApi.ts web/src/auth/authApi.test.ts
git commit -m "Add verifyEmail to the SPA auth API (MBD-63)"
```

---

### Task 3: The `/verificar-email` confirmation screen

**Files:**
- Modify: `web/src/ui/icons.tsx`
- Modify: `web/src/styles/auth.css` (next to `.auth-mail`, about line 183, and after `.auth-card a`, about line 241)
- Create: `web/src/pages/VerifyEmailPage.tsx`
- Modify: `web/src/App.tsx`
- Test: `web/src/pages/VerifyEmailPage.test.tsx`

**Interfaces:**
- Consumes: `verifyEmail(token): Promise<void>` and `ApiError` (Task 2). `AuthLayout({ titleId, centered, children })`, `Alert({ title, children })`, `renderApp(entry)`, and `fakeApi`/`problem`/`json`/`tokenResponse` (existing).
- Produces: `export function VerifyEmailPage()`, routed at `/verificar-email`. Also `export function CheckIcon()` and `export function WarningIcon()` in `ui/icons.tsx`.

- [ ] **Step 1: Write the failing tests**

Create `web/src/pages/VerifyEmailPage.test.tsx`:

```tsx
import { afterEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { fakeApi, json, problem, tokenResponse } from '../test/fakeApi';
import { renderApp } from '../test/renderApp';

const SIGNED_OUT = { 'POST /api/v1/auth/refresh': () => problem(401) };
const verified = () => json(200, { status: 'verified' });

describe('VerifyEmailPage', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('confirms a valid link with "Tudo certo!" and one Entrar button', async () => {
    const { calls } = fakeApi({ ...SIGNED_OUT, 'GET /api/v1/auth/verify': verified });
    renderApp('/verificar-email?token=abc_DEF-123');

    const heading = await screen.findByRole('heading', { name: 'Tudo certo!' });
    expect(screen.getByText('Seu email foi confirmado. Agora é só entrar e começar a avaliar seus álbuns.')).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Entrar' })).toHaveLength(1);
    expect(screen.queryByRole('button')).toBeNull();
    expect(heading).toHaveFocus();
    expect(calls.filter((c) => c.url.startsWith('/api/v1/auth/verify'))).toHaveLength(1);
    expect(calls.find((c) => c.url.startsWith('/api/v1/auth/verify'))!.url).toBe('/api/v1/auth/verify?token=abc_DEF-123');
  });

  it('shows "Tudo certo!" again when the link was already used (the API answers 200)', async () => {
    fakeApi({ ...SIGNED_OUT, 'GET /api/v1/auth/verify': [verified, verified] });
    const first = renderApp('/verificar-email?token=used');
    expect(await screen.findByRole('heading', { name: 'Tudo certo!' })).toBeInTheDocument();
    first.unmount();

    renderApp('/verificar-email?token=used');

    expect(await screen.findByRole('heading', { name: 'Tudo certo!' })).toBeInTheDocument();
  });

  it('shows "Link inválido ou expirado" when the API rejects the token (400)', async () => {
    fakeApi({ ...SIGNED_OUT, 'GET /api/v1/auth/verify': () => problem(400) });
    renderApp('/verificar-email?token=tampered');

    const heading = await screen.findByRole('heading', { name: 'Link inválido ou expirado' });
    expect(
      screen.getByText(
        'Este link de confirmação não vale mais. Entre com seu email e senha: se a conta ainda não estiver ativa, mostramos como receber um novo link.',
      ),
    ).toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Entrar' })).toHaveLength(1);
    expect(heading).toHaveFocus();
  });

  it('a link without a token is invalid without asking the API', async () => {
    const { calls } = fakeApi(SIGNED_OUT);
    renderApp('/verificar-email?token=');

    expect(await screen.findByRole('heading', { name: 'Link inválido ou expirado' })).toBeInTheDocument();
    expect(calls.some((c) => c.url.startsWith('/api/v1/auth/verify'))).toBe(false);
  });

  it('does not call a working link invalid when the API is unreachable', async () => {
    fakeApi({ ...SIGNED_OUT, 'GET /api/v1/auth/verify': () => problem(503) });
    renderApp('/verificar-email?token=abc');

    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível confirmar agora. Tente de novo em instantes.');
    expect(screen.queryByRole('heading', { name: 'Link inválido ou expirado' })).toBeNull();
  });

  it('"Tentar de novo" retries after a failure and then confirms', async () => {
    fakeApi({
      ...SIGNED_OUT,
      'GET /api/v1/auth/verify': [() => Promise.reject(new TypeError('Failed to fetch')), verified],
    });
    const user = userEvent.setup();
    renderApp('/verificar-email?token=abc');

    await user.click(await screen.findByRole('button', { name: 'Tentar de novo' }));

    expect(await screen.findByRole('heading', { name: 'Tudo certo!' })).toBeInTheDocument();
  });

  it('shows the outcome to a visitor who is already signed in', async () => {
    fakeApi({
      'POST /api/v1/auth/refresh': () => tokenResponse(),
      'GET /api/v1/accounts/me': () => json(200, { id: '1', email: 'ana@exemplo.com', username: 'ana' }),
      'GET /api/v1/auth/verify': verified,
    });
    renderApp('/verificar-email?token=abc');

    expect(await screen.findByRole('heading', { name: 'Tudo certo!' })).toBeInTheDocument();
  });

  it('Entrar goes to the login screen', async () => {
    fakeApi({ ...SIGNED_OUT, 'GET /api/v1/auth/verify': () => problem(400) });
    const user = userEvent.setup();
    renderApp('/verificar-email?token=x');

    await user.click(await screen.findByRole('link', { name: 'Entrar' }));

    expect(await screen.findByRole('heading', { name: 'Entrar' })).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
  });
});
```

The signed-in test stubs `GET /api/v1/accounts/me` because `AuthProvider` calls `fetchMe()` after a successful refresh on page load (`AuthProvider.tsx:42`).

- [ ] **Step 2: Run them to verify they fail**

Run: `npx vitest run src/pages/VerifyEmailPage.test.tsx`
Expected: FAIL. Every test times out in `findByRole` because no route matches `/verificar-email`.

- [ ] **Step 3: Add the two icons**

Append to `web/src/ui/icons.tsx` (the paths are copied from the mockup):

```tsx
export function CheckIcon() {
  return (
    <svg {...base} width={30} height={30}>
      <path d="M5 12.5 10 17.5 19 7" />
    </svg>
  );
}

export function WarningIcon() {
  return (
    <svg {...base} width={30} height={30}>
      <path d="M12 9v5" />
      <path d="M12 4 2.8 19.5h18.4z" />
      <circle cx="12" cy="17" r=".6" fill="currentColor" />
    </svg>
  );
}
```

- [ ] **Step 4: Add the styles**

In `web/src/styles/auth.css`, right after the `.auth-mail { … }` block:

```css
.auth-mail--bad {
  border-color: var(--err);
  color: var(--err);
}
```

and right after the `.auth-card a { … }` block (it must come later and be more specific, because `.auth-card a` would otherwise recolor the button text):

```css
/* A router <Link> that looks like the primary button (MBD-63 "Entrar"). */
.auth-card a.btn {
  display: block;
  text-align: center;
  text-decoration: none;
  color: var(--on-coral);
}
```

- [ ] **Step 5: Create the page**

Create `web/src/pages/VerifyEmailPage.tsx`:

```tsx
import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { ApiError, verifyEmail } from '../auth/authApi';
import { Alert } from '../ui/Alert';
import { AuthLayout } from '../ui/AuthLayout';
import { CheckIcon, WarningIcon } from '../ui/icons';

type Outcome = 'checking' | 'verified' | 'invalid' | 'unavailable';

/**
 * Where the verification email's link lands (MBD-63). Open to everyone, signed in or not.
 * A reused link is still "verified" (the API answers 200). Only a 400 means the link is bad;
 * a network or server failure says nothing about the link, so it offers a retry instead.
 */
export function VerifyEmailPage() {
  const [params] = useSearchParams();
  const token = params.get('token')?.trim() ?? '';
  const [result, setResult] = useState<Outcome>('checking');
  const [attempt, setAttempt] = useState(0);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const outcome: Outcome = token ? result : 'invalid';

  useEffect(() => {
    if (!token) {
      return;
    }
    // StrictMode runs this twice in dev: both calls answer 200, and the first one's result is dropped.
    let current = true;
    setResult('checking');
    verifyEmail(token).then(
      () => current && setResult('verified'),
      (error: unknown) => current && setResult(error instanceof ApiError && error.status === 400 ? 'invalid' : 'unavailable'),
    );
    return () => {
      current = false;
    };
  }, [token, attempt]);

  useEffect(() => {
    if (outcome === 'verified' || outcome === 'invalid') {
      headingRef.current?.focus();
    }
  }, [outcome]);

  if (outcome === 'checking' || outcome === 'unavailable') {
    return (
      <AuthLayout titleId="confirm-title" centered>
        <h1 id="confirm-title" role="status">
          Confirmando seu email…
        </h1>
        {outcome === 'unavailable' && (
          <>
            <Alert title="Não foi possível confirmar agora.">Tente de novo em instantes.</Alert>
            <button className="btn btn--full" type="button" onClick={() => setAttempt((n) => n + 1)}>
              Tentar de novo
            </button>
          </>
        )}
      </AuthLayout>
    );
  }

  const verified = outcome === 'verified';
  return (
    <AuthLayout titleId="confirm-title" centered>
      <div className={verified ? 'auth-mail' : 'auth-mail auth-mail--bad'} aria-hidden="true">
        {verified ? <CheckIcon /> : <WarningIcon />}
      </div>
      <h1 id="confirm-title" tabIndex={-1} ref={headingRef}>
        {verified ? 'Tudo certo!' : 'Link inválido ou expirado'}
      </h1>
      <p className="auth-lead">
        {verified
          ? 'Seu email foi confirmado. Agora é só entrar e começar a avaliar seus álbuns.'
          : 'Este link de confirmação não vale mais. Entre com seu email e senha: se a conta ainda não estiver ativa, mostramos como receber um novo link.'}
      </p>
      <Link className="btn btn--full" to="/entrar">
        Entrar
      </Link>
    </AuthLayout>
  );
}
```

- [ ] **Step 6: Route it (not guest-only)**

In `web/src/App.tsx`, add the import and route:

```tsx
import { VerifyEmailPage } from './pages/VerifyEmailPage';
```

```tsx
      <Route path="/verifique-email" element={<GuestOnly><VerifyEmailSentPage /></GuestOnly>} />
      {/* Not GuestOnly: the emailed link may open in a browser that already has a session (MBD-63). */}
      <Route path="/verificar-email" element={<VerifyEmailPage />} />
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `npx vitest run src/pages/VerifyEmailPage.test.tsx`
Expected: PASS (8 tests).

Run: `npm test && npx tsc -b && npm run build`
Expected: all tests PASS, no type errors, the build succeeds, and `git status` shows **no** change under `web/src/api-client/` (the API contract did not change).

- [ ] **Step 8: Check it against the mockup**

Run `npm run dev` (with the api running, or not: 400 and 503 are easy to get either way) and open `http://localhost:5173/verificar-email?token=nope` at desktop width and at 390 px wide in devtools. Compare it side by side with `email-verification.html`. Check:
- a centered card with a circular icon (coral check / `--err` warning);
- the heading and text as specified;
- one full-width coral **Entrar** whose text is dark (`--on-coral`) and not underlined;
- a coral focus ring when tabbing to Entrar.

- [ ] **Step 9: Commit**

```bash
git add web/src/ui/icons.tsx web/src/styles/auth.css web/src/pages/VerifyEmailPage.tsx \
        web/src/pages/VerifyEmailPage.test.tsx web/src/App.tsx
git commit -m "Open the verification link in a confirmation screen with Entrar (MBD-63)"
```

---

### Task 4: Check the acceptance criteria end to end (local stack)

No code changes. This task checks the ticket's "Verify" line against the real api and SPA.

- [ ] **Step 1: Start the stack**

Start Postgres and the api the way `README.md` describes for local dev, with `MUSICBOXD_PUBLIC_BASE_URL=http://localhost:5173` so the link points at the Vite dev server. Locally the mail sender is `LoggingMailSender`, so the email body (with the link) shows up in the api log. Run `npm run dev` in `web/`.

- [ ] **Step 2: Walk through the acceptance criteria**

1. At `http://localhost:5173/cadastro`, register a new account. You land on "Verifique seu email".
2. Copy the link from the api log. It must be `http://localhost:5173/verificar-email?token=…`. Open it. → "Tudo certo!"
3. Click **Entrar**. → the login screen. Log in with the same credentials. → you are signed in (home page).
4. Open the same link again (you are signed in now). → "Tudo certo!" again, with no redirect.
5. Change one character of the token and open it. → "Link inválido ou expirado". **Entrar** → login screen (or `/` if you are still signed in, which is expected).
6. Open `/verificar-email` with no token. → "Link inválido ou expirado".
7. Stop the api and open a fresh link. → the "Não foi possível confirmar agora." alert with **Tentar de novo**. Start the api again and click it. → "Tudo certo!"

Note anything that differs in the PR description.

- [ ] **Step 3: Open the PR**

```bash
git push -u origin story/mbd-63-verify-email-page
gh pr create --title "MBD-63: the verification link opens a confirmation screen in the app" --body "<summary, the two [ASSUMPTION] states (checking, unavailable) for review, and the Task 4 results>

🤖 Generated with [Claude Code](https://claude.com/claude-code)"
```

After deploy, the existing runbook already covers production config: `MUSICBOXD_PUBLIC_BASE_URL` is the site root, so the new link resolves to the SPA through nginx `try_files` with no host change.
