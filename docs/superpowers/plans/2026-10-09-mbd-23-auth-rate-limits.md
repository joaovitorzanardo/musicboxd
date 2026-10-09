# MBD-23 Per-User Rate Limits on Login, Registration, and Verification Email — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repeated login attempts, repeated registrations, and repeated verification-email requests against one account (one email address) are each answered `429` with `Retry-After` past their configured limit. A per-client-IP limit on the same three endpoints stops one caller from cycling through many emails.

**Architecture:** The MBD-13 limiter keys a bucket off the request before the controller runs (`@RateLimited` + `HandlerInterceptor`), so it can only see the principal or the IP. These three endpoints are anonymous, and "one account" means the email **in the JSON body**, which the interceptor cannot read without consuming the stream. So each endpoint gets two checks: (1) the existing `@RateLimited` annotation, keyed per IP by the default `principalOrIp` resolver, and (2) a new programmatic check, `RateLimits.check(policy, key)`, that the controller calls right after `@Valid` binding, with the key `email:<sha256(normalized email)>`. `RateLimits` is a thin bean over the existing `RateLimiter` and throws the existing `RateLimitExceededException`, so the 429 body and `Retry-After` header are the ones MBD-13 already renders. The interceptor is changed to call `RateLimits` too, so there is one throw site. The SPA's login and register screens get a "too many attempts" alert for 429 (the verification screen has one already).

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring MVC, springdoc), Bucket4j + Caffeine (already in the build), JUnit 5 / MockMvc / AssertJ, Testcontainers PostgreSQL; React 19 + Vitest + Testing Library in `web/`.

**Spec:** Jira MBD-23 / `_bmad-output/initiative-musicboxd/epic-contas-acesso/story-per-user-rate-limits-on-login-registration-and-verification.md`. Verify line: "Repeated login attempts, repeated registrations, and repeated verification-email requests from one account are each throttled past their configured limit." Architecture: `_bmad-output/planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md` AD-10 ("Two rate-limit layers: nginx `limit_req` per IP, and Spring per-user limits on login, registration, email sending, and upload URL issuance"). Mechanism: MBD-13 plan `docs/superpowers/plans/2026-10-06-mbd-13-rate-limiting.md`.

**Branch:** MBD-22 must be merged first (Task 5 edits the SPA pages it added). `git switch main && git pull && git switch -c story/mbd-23-auth-rate-limits`. Commit messages end with `(MBD-23)` and the `Co-Authored-By` trailer. Run Gradle from `api/` (`./gradlew` in Git Bash, `.\gradlew.bat` in PowerShell). Docker must be running for Testcontainers. The working tree shows many files as modified that differ only in line endings (CRLF): never `git add -A`, add files by path.

## Decisions (defaults chosen for this story)

> **2026-10-09, after the final review:** `register-per-email` was raised from 3 to 8 per hour. Every attempt costs a token, including a 409 for a taken username, so 3 locked a real person out of sign-up after a few username tries (`AuthFlowTest.tryingSeveralTakenUsernamesDoesNotLockTheEmailOutOfSignUp`). The runbook now says tuning cannot fix the targeted lockout, and it recreates the api before checking the override.

| Question | Decision |
|---|---|
| What "per user" means on anonymous endpoints | The **email address in the request body**, normalized exactly as `AccountService.normalize` does (strip + lowercase). It is the same key whether the account exists or not. |
| Why also per IP | The per-email key alone lets one caller try 10 000 different emails. Each endpoint keeps an IP-keyed `@RateLimited` too (the default `principalOrIp` resolver). nginx's 10 r/s per IP stays as the outer layer. |
| How the email key is read | Programmatically, in the controller after `@Valid`: `rateLimits.check("<policy>", EmailRateLimitKey.of(request.email()))`. No body-caching filter and no change to `@RateLimited`. |
| Key format | `email:` + SHA-256 hex of the normalized email (reuses `OpaqueTokens.hash`). The key length is fixed, so a 1 MB "email" in a login body cannot pin 1 MB per bucket (`LoginRequest.email` has no size cap), and no plaintext addresses sit in the limiter's memory. |
| Order of checks | IP (interceptor, before binding) → bean validation (400) → email (controller) → service. A 400 body costs an IP token but no email token. A throttled login never reaches bcrypt. |
| What a login attempt costs | **Every** attempt, successful or not, costs one email token and one IP token. Counting only failures changes nothing for brute force and needs a peek API that the limiter does not have. |
| Targeted lockout | Anyone who knows an address can keep its login throttled. Accepted for the MVP: refill is continuous, so after the attacker stops, the owner waits at most `refill-period / capacity` (90 s with the values below). Revisit if it gets abused (e.g. allow a login from an IP that already holds a session for that account). |
| Account enumeration | A throttled request answers 429 the same way for known and unknown emails, at the same count. Registration already reveals "email taken" (409, MBD-17); this story adds no new signal. |
| Production numbers | `login-per-ip` 20 / 15m · `login-per-email` 10 / 15m · `register-per-ip` 10 / 1h · `register-per-email` 3 / 1h · `verification-email-per-ip` 3 / 15m (unchanged, renamed) · `verification-email-per-email` 5 / 24h. Each one can be overridden from `/etc/musicboxd/api.env` without a rebuild (runbook). |
| Policy names | `<endpoint>-per-ip` and `<endpoint>-per-email`. The MBD-18 policy `verification-email` is renamed `verification-email-per-ip`. It is in-memory state, so a rename loses nothing. A dashed map key cannot be overridden by a plain env var, so the runbook overrides through `SPRING_APPLICATION_JSON`. |
| SPA | Login and register show "Muitas tentativas. Espere alguns minutos e tente de novo." on 429 instead of the generic "unavailable" alert. `Retry-After` is not shown (`ApiError` does not carry headers; minutes is accurate enough). |
| Demo endpoint (`/api/v1/demo/rate-limited`) | Untouched. Its removal belongs to the MBD-25 refactor sweep. |

## Global Constraints

- Two rate-limit layers: nginx `limit_req` per IP, and Spring per-user limits on login, registration, and email sending (AD-10). This story adds no nginx change.
- Limiter state stays in memory, single host, no new dependency (AD-11, MBD-13). `MAX_TRACKED_KEYS` (10 000 per policy) is unchanged.
- A throttled request is `429`, `Content-Type: application/problem+json`, body `{"type":"about:blank","title":"Too Many Requests","status":429,"detail":"Rate limit exceeded. Retry later."}`, header `Retry-After` in whole seconds (MBD-13's `RateLimitExceededException`). No new error shape.
- A 429 must never reveal whether an account exists.
- Email normalization has one owner: `AccountService.normalize`. The rate-limit key must call it, not copy it.
- `ratelimit` must not import from `accounts` (accounts already imports `ratelimit.RateLimited`).
- Every policy a controller names must exist in `api/src/main/resources/application.yml`. An unknown name is a 500 at request time (`RateLimiter.tryAcquire` throws `IllegalArgumentException`).
- Java sources use tabs. Routes are documented by springdoc (`@ApiResponse(responseCode = "429", ...)`).
- Out of scope: upload-URL limits (the uploads epic), nginx changes, CAPTCHA, account lockout state in the database, limits on `/refresh` and `/verify`.

## Review Focus

- **The same address typed differently** (`" Ana@Exemplo.com "` vs `ana@exemplo.com`) must share one bucket, or the per-email limit is bypassed by changing case. (Task 2: `aThrottledEmailIsRefusedEvenWithTheRightPassword` uses case/padding variants; `EmailRateLimitKeyTest.sameAddressInAnyCaseOrPaddingSharesAKey`.)
- **The right password after the limit** must still get 429, not 200, or the limit does not stop brute force. (Task 2: `aThrottledEmailIsRefusedEvenWithTheRightPassword`.)
- **Unknown emails** must be throttled at the same count with the same answer as known ones, on login and on resend. (Task 2: `unknownEmailsAreThrottledLikeKnownOnes`; Task 4: `verificationEmailThrottleDoesNotRevealWhetherTheAccountExists`.)
- **A throttled registration has no side effect**: no account row, no email sent (SES cost is why AD-10 exists). (Task 3: `registrationIsThrottledPerIpAndAThrottledOneCreatesNothing`.)
- **Huge or blank emails** must give 400/401, never 500, and never an unbounded key. (Task 2: `oversizedAndBlankEmailsAreAnsweredNotCrashed`; `EmailRateLimitKeyTest.keyLengthIsFixedWhateverTheInput`.)

---

## File Structure

**Programmatic check (Task 1)**
- Create `api/src/main/java/com/musicboxd/api/ratelimit/RateLimits.java`: `check(policy, key)`; throws `RateLimitExceededException`.
- Modify `api/src/main/java/com/musicboxd/api/ratelimit/RateLimitConfig.java`: build one `RateLimits`, expose it as a bean, and pass it to the interceptor.
- Modify `api/src/main/java/com/musicboxd/api/ratelimit/RateLimitInterceptor.java`: call `RateLimits.check` instead of `RateLimiter` directly.
- Create `api/src/test/java/com/musicboxd/api/ratelimit/RateLimitsTest.java`.

**Login (Task 2)**
- Create `api/src/main/java/com/musicboxd/api/accounts/EmailRateLimitKey.java`: `static String of(String email)`.
- Modify `api/src/main/java/com/musicboxd/api/accounts/AuthController.java`: inject `RateLimits`; `@RateLimited(policy = "login-per-ip")` + email check on `login`; 429 in OpenAPI.
- Modify `api/src/main/resources/application.yml`: `login-per-ip`, `login-per-email`.
- Modify `api/src/test/resources/config/application.yml`: lift the per-IP policies for the shared test context (every MockMvc request comes from `127.0.0.1`).
- Create `api/src/test/java/com/musicboxd/api/accounts/EmailRateLimitKeyTest.java`.
- Create `api/src/test/java/com/musicboxd/api/accounts/AuthRateLimitTest.java`: the acceptance tests, with their own small policy numbers.

**Registration (Task 3)**
- Modify `AuthController.java` (`register`), `application.yml`, `AuthRateLimitTest.java`.

**Verification email, policy rename, runbook (Task 4)**
- Modify `AuthController.java` (`resendVerification`), `application.yml` (rename + new policy), `AuthRateLimitTest.java`.
- Create `api/src/test/java/com/musicboxd/api/ratelimit/RateLimitPoliciesTest.java`: every policy the controllers name exists in the real `application.yml`.
- Create `docs/runbooks/runbook-mbd-23-auth-rate-limits.md`: tunables + live verification.
- Modify `docs/runbooks/runbook-mbd-13-rate-limits.md`: point its tunables table at the new runbook.

**SPA (Task 5)**
- Modify `web/src/pages/LoginPage.tsx`, `web/src/pages/RegisterPage.tsx` and their `.test.tsx`.

---

### Task 1: A programmatic rate-limit check

**Files:**
- Create: `api/src/main/java/com/musicboxd/api/ratelimit/RateLimits.java`
- Modify: `api/src/main/java/com/musicboxd/api/ratelimit/RateLimitConfig.java`
- Modify: `api/src/main/java/com/musicboxd/api/ratelimit/RateLimitInterceptor.java`
- Test: `api/src/test/java/com/musicboxd/api/ratelimit/RateLimitsTest.java`

**Interfaces:**
- Consumes: `RateLimiter.tryAcquire(String policyName, String key) → Decision(boolean allowed, Duration retryAfter)`; `RateLimitExceededException(Duration retryAfter)` (both MBD-13).
- Produces: bean `com.musicboxd.api.ratelimit.RateLimits` with `public void check(String policy, String key)`, which throws `RateLimitExceededException` (rendered as 429 + `Retry-After` by `RateLimitExceptionHandler`) past the limit, and `IllegalArgumentException` for an unknown policy.

- [ ] **Step 1: Write the failing test**

`RateLimitsTest.java`:

```java
package com.musicboxd.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** The programmatic form of {@link RateLimited}, for keys only the controller can see (MBD-23). */
class RateLimitsTest {

	private final RateLimits limits = new RateLimits(
			new RateLimiter(Map.of("p", new RateLimitPolicy(2, Duration.ofHours(1))), 100));

	@Test
	void allowsUpToCapacityThenThrowsA429WithRetryAfter() {
		limits.check("p", "k");
		limits.check("p", "k");

		assertThatThrownBy(() -> limits.check("p", "k"))
			.isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
				assertThat(e.getStatusCode().value()).isEqualTo(429);
				// 2 per hour, refilled continuously: the next token is 30 minutes away.
				assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1800");
			});
	}

	@Test
	void keysHaveIndependentBuckets() {
		limits.check("p", "k");
		limits.check("p", "k");

		limits.check("p", "other");
	}

	@Test
	void anUnknownPolicyFailsLoudly() {
		assertThatThrownBy(() -> limits.check("nope", "k")).isInstanceOf(IllegalArgumentException.class);
	}
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests '*RateLimitsTest'`
Expected: compilation FAILS with `cannot find symbol: class RateLimits`.

- [ ] **Step 3: Implement `RateLimits` and use it from the interceptor**

`RateLimits.java`:

```java
package com.musicboxd.api.ratelimit;

/**
 * The programmatic form of {@link RateLimited}: for a caller key that only the controller can
 * see, such as an email in the request body (MBD-23). Shares the interceptor's buckets and 429.
 */
public class RateLimits {

	private final RateLimiter limiter;

	public RateLimits(RateLimiter limiter) {
		this.limiter = limiter;
	}

	/**
	 * Takes one request from {@code key}'s bucket under {@code policy}, configured at
	 * {@code musicboxd.rate-limit.policies.<policy>}. Past the limit it throws
	 * {@link RateLimitExceededException}, which is answered 429 with {@code Retry-After}.
	 */
	public void check(String policy, String key) {
		var decision = limiter.tryAcquire(policy, key);
		if (!decision.allowed()) {
			throw new RateLimitExceededException(decision.retryAfter());
		}
	}
}
```

`RateLimitConfig.java` — replace the `limiter` field and constructor, add the bean, and pass `rateLimits` to the interceptor:

```java
	private final RateLimits rateLimits;
	private final BeanFactory beans;

	public RateLimitConfig(RateLimitProperties properties, BeanFactory beans) {
		this.rateLimits = new RateLimits(new RateLimiter(properties.policies(), MAX_TRACKED_KEYS));
		this.beans = beans;
	}

	/** One instance for the interceptor and for controllers, so both draw from the same buckets. */
	@Bean
	RateLimits rateLimits() {
		return rateLimits;
	}

	@Bean("principalOrIp")
	RateLimitKeyResolver principalOrIpKeyResolver() {
		return new PrincipalOrIpKeyResolver();
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new RateLimitInterceptor(rateLimits, beans)).addPathPatterns("/api/**");
	}
```

Update the class Javadoc to: `/** Wires the per-user limiter (AD-10 layer 2) into every {@code /api/**} request, and exposes it to controllers as {@link RateLimits}. */`

`RateLimitInterceptor.java` — the field, the constructor and the end of `preHandle`:

```java
	private final RateLimits rateLimits;
	private final BeanFactory beans;

	public RateLimitInterceptor(RateLimits rateLimits, BeanFactory beans) {
		this.rateLimits = rateLimits;
		this.beans = beans;
	}
```

```java
		String key = beans.getBean(limit.keyResolver(), RateLimitKeyResolver.class).resolve(request);
		rateLimits.check(limit.policy(), key);
		return true;
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests '*RateLimitsTest' --tests '*RateLimiterTest' --tests '*DemoRateLimitControllerTest' --tests '*AuthFlowTest'`
Expected: PASS. The demo and the MBD-18 resend test prove the interceptor still throttles through the new path.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/ratelimit/RateLimits.java api/src/main/java/com/musicboxd/api/ratelimit/RateLimitConfig.java api/src/main/java/com/musicboxd/api/ratelimit/RateLimitInterceptor.java api/src/test/java/com/musicboxd/api/ratelimit/RateLimitsTest.java
git commit -m "Expose the per-user limiter to controllers as RateLimits.check (MBD-23)"
```

---

### Task 2: Login limits, per email and per IP

**Files:**
- Create: `api/src/main/java/com/musicboxd/api/accounts/EmailRateLimitKey.java`
- Modify: `api/src/main/java/com/musicboxd/api/accounts/AuthController.java`
- Modify: `api/src/main/resources/application.yml`
- Modify: `api/src/test/resources/config/application.yml`
- Test: `api/src/test/java/com/musicboxd/api/accounts/EmailRateLimitKeyTest.java`
- Test: `api/src/test/java/com/musicboxd/api/accounts/AuthRateLimitTest.java`

**Interfaces:**
- Consumes: `RateLimits.check(String policy, String key)` (Task 1); `AccountService.normalize(String)` and `OpaqueTokens.hash(String)` (package-private, `accounts`).
- Produces: `EmailRateLimitKey.of(String email) → "email:" + 64 hex chars` (package-private, used by Tasks 3 and 4); `AuthController` takes a `RateLimits` constructor argument; the test class `AuthRateLimitTest` with helpers `freshIp()`, `login(email, password, ip)`, `register(email, username, ip)`, `resend(email, ip)` that Tasks 3 and 4 add tests to; policies `login-per-ip`, `login-per-email`.

- [ ] **Step 1: Write the failing key test**

`EmailRateLimitKeyTest.java`:

```java
package com.musicboxd.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailRateLimitKeyTest {

	@Test
	void sameAddressInAnyCaseOrPaddingSharesAKey() {
		assertThat(EmailRateLimitKey.of("  Ana@Exemplo.COM ")).isEqualTo(EmailRateLimitKey.of("ana@exemplo.com"));
	}

	@Test
	void differentAddressesGetDifferentKeys() {
		assertThat(EmailRateLimitKey.of("ana@exemplo.com")).isNotEqualTo(EmailRateLimitKey.of("bia@exemplo.com"));
	}

	@Test
	void keyLengthIsFixedWhateverTheInput() {
		String huge = EmailRateLimitKey.of("a".repeat(100_000) + "@exemplo.com");

		assertThat(huge).startsWith("email:").hasSize(EmailRateLimitKey.of("a@b").length());
		assertThat(huge).doesNotContain("exemplo");
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew test --tests '*EmailRateLimitKeyTest'`
Expected: compilation FAILS with `cannot find symbol: variable EmailRateLimitKey`.

- [ ] **Step 3: Implement the key**

`EmailRateLimitKey.java`:

```java
package com.musicboxd.api.accounts;

/**
 * The per-account rate-limit key for anonymous auth endpoints (MBD-23): the email as the account
 * stores it, hashed so every key has the same size and no address sits in the limiter's memory.
 */
final class EmailRateLimitKey {

	private EmailRateLimitKey() {
	}

	static String of(String email) {
		return "email:" + OpaqueTokens.hash(AccountService.normalize(email));
	}
}
```

Run: `./gradlew test --tests '*EmailRateLimitKeyTest'`
Expected: PASS.

- [ ] **Step 4: Write the failing login acceptance tests**

`AuthRateLimitTest.java` (Tasks 3 and 4 add tests to this class; its properties already hold every policy this story uses):

```java
package com.musicboxd.api.accounts;

import static com.musicboxd.api.accounts.AccountServiceTest.markVerified;
import static com.musicboxd.api.accounts.AccountServiceTest.uniqueEmail;
import static com.musicboxd.api.accounts.AccountServiceTest.uniqueUsername;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.mail.MailTestConfiguration;
import com.musicboxd.api.mail.RecordingMailSender;

/**
 * MBD-23 acceptance: login, registration and verification email are each throttled per email
 * (one account) and per client IP. The policies are overridden here so the test owns the numbers;
 * every request picks its own IP unless a test is about the per-IP limit.
 */
@SpringBootTest(properties = {
	"musicboxd.rate-limit.policies.login-per-ip.capacity=5",
	"musicboxd.rate-limit.policies.login-per-ip.refill-period=1h",
	"musicboxd.rate-limit.policies.login-per-email.capacity=3",
	"musicboxd.rate-limit.policies.login-per-email.refill-period=1h",
	"musicboxd.rate-limit.policies.register-per-ip.capacity=3",
	"musicboxd.rate-limit.policies.register-per-ip.refill-period=1h",
	"musicboxd.rate-limit.policies.register-per-email.capacity=2",
	"musicboxd.rate-limit.policies.register-per-email.refill-period=1h",
	"musicboxd.rate-limit.policies.verification-email-per-ip.capacity=10",
	"musicboxd.rate-limit.policies.verification-email-per-ip.refill-period=1h",
	"musicboxd.rate-limit.policies.verification-email-per-email.capacity=2",
	"musicboxd.rate-limit.policies.verification-email-per-email.refill-period=1h" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfiguration.class })
class AuthRateLimitTest {

	private static final String PASSWORD = "correct-horse";
	private static final AtomicInteger IPS = new AtomicInteger();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private RecordingMailSender mail;

	// --- login ---

	@Test
	void loginIsThrottledPerEmailWhicheverIpTheAttemptsComeFrom() throws Exception {
		String email = uniqueEmail();
		register(email, uniqueUsername(), freshIp()).andExpect(status().isCreated());

		for (int i = 0; i < 3; i++) {
			login(email, "wrong-password", freshIp()).andExpect(status().isUnauthorized());
		}
		login(email, "wrong-password", freshIp())
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.status").value(429));
		// Another account is not affected.
		login(uniqueEmail(), "wrong-password", freshIp()).andExpect(status().isUnauthorized());
	}

	@Test
	void aThrottledEmailIsRefusedEvenWithTheRightPassword() throws Exception {
		String email = registerVerified();

		// Case and padding variants count against the same account.
		login("  " + email.toUpperCase() + " ", "wrong-password", freshIp()).andExpect(status().isUnauthorized());
		login(email.toUpperCase(), "wrong-password", freshIp()).andExpect(status().isUnauthorized());
		login(email, "wrong-password", freshIp()).andExpect(status().isUnauthorized());

		login(email, PASSWORD, freshIp()).andExpect(status().isTooManyRequests());
	}

	@Test
	void unknownEmailsAreThrottledLikeKnownOnes() throws Exception {
		String nobody = uniqueEmail();
		for (int i = 0; i < 3; i++) {
			login(nobody, "wrong-password", freshIp()).andExpect(status().isUnauthorized());
		}
		login(nobody, "wrong-password", freshIp()).andExpect(status().isTooManyRequests());
	}

	@Test
	void loginIsThrottledPerIpAcrossEmails() throws Exception {
		String ip = freshIp();
		for (int i = 0; i < 5; i++) {
			login(uniqueEmail(), "wrong-password", ip).andExpect(status().isUnauthorized());
		}
		login(uniqueEmail(), "wrong-password", ip).andExpect(status().isTooManyRequests());
		login(uniqueEmail(), "wrong-password", freshIp()).andExpect(status().isUnauthorized());
	}

	@Test
	void oversizedAndBlankEmailsAreAnsweredNotCrashed() throws Exception {
		login("a".repeat(10_000) + "@example.com", "wrong-password", freshIp()).andExpect(status().isUnauthorized());
		login("   ", "wrong-password", freshIp()).andExpect(status().isBadRequest());
	}

	@Test
	void openApiDocumentsTheLoginThrottle() throws Exception {
		mockMvc.perform(get("/api/v1/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['429']").exists());
	}

	// --- helpers ---

	/** A client IP no other request in this class has used, so only the bucket under test fills up. */
	private static String freshIp() {
		int n = IPS.incrementAndGet();
		return "10.23." + (n / 250) + "." + (n % 250 + 1);
	}

	private String registerVerified() throws Exception {
		String email = uniqueEmail();
		register(email, uniqueUsername(), freshIp()).andExpect(status().isCreated());
		UUID id = jdbc.sql("SELECT id FROM accounts.accounts WHERE email = :email")
			.param("email", email)
			.query(UUID.class)
			.single();
		markVerified(jdbc, id);
		return email;
	}

	private ResultActions login(String email, String password, String clientIp) throws Exception {
		return send("/api/v1/auth/login", clientIp, """
				{"email":"%s","password":"%s"}""".formatted(email, password));
	}

	private ResultActions register(String email, String username, String clientIp) throws Exception {
		return send("/api/v1/auth/register", clientIp, """
				{"email":"%s","password":"%s","username":"%s"}""".formatted(email, PASSWORD, username));
	}

	private ResultActions resend(String email, String clientIp) throws Exception {
		return send("/api/v1/auth/verification-email", clientIp, """
				{"email":"%s"}""".formatted(email));
	}

	private ResultActions send(String path, String clientIp, String json) throws Exception {
		return mockMvc.perform(post(path)
			.with(request -> {
				request.setRemoteAddr(clientIp);
				return request;
			})
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}
}
```

- [ ] **Step 5: Run them to verify they fail**

Run: `./gradlew test --tests '*AuthRateLimitTest'`
Expected: FAIL. `loginIsThrottledPerEmailWhicheverIpTheAttemptsComeFrom`, `aThrottledEmailIsRefusedEvenWithTheRightPassword` and `unknownEmailsAreThrottledLikeKnownOnes` get 401 where 429 is expected; `aThrottledEmail...` may get 200; `loginIsThrottledPerIpAcrossEmails` gets 401; `openApiDocumentsTheLoginThrottle` finds no `429`. `oversizedAndBlankEmailsAreAnsweredNotCrashed` already passes.

- [ ] **Step 6: Throttle login**

`AuthController.java` — add the import `com.musicboxd.api.ratelimit.RateLimits`, a field, and the constructor parameter:

```java
	private final EmailVerificationService verifications;
	private final RateLimits rateLimits;

	AuthController(AccountService accounts, TokenService tokens, RefreshTokenService refreshTokens,
			RefreshTokenProperties refreshProps, EmailVerificationService verifications, RateLimits rateLimits) {
		this.accounts = accounts;
		this.tokens = tokens;
		this.refreshTokens = refreshTokens;
		this.refreshProps = refreshProps;
		this.verifications = verifications;
		this.rateLimits = rateLimits;
	}
```

Replace the `login` method:

```java
	@PostMapping("/login")
	@RateLimited(policy = "login-per-ip")
	@ApiResponse(responseCode = "200",
			description = "A short-lived bearer access token; the refresh token is set as an HttpOnly cookie")
	@ApiResponse(responseCode = "401", description = "Unknown email or wrong password (indistinguishable)")
	@ApiResponse(responseCode = "403",
			description = "Right password, email not verified yet (type urn:musicboxd:problem:email-not-verified)")
	@ApiResponse(responseCode = "429",
			description = "Too many attempts for this email or from this caller, right password or not; see Retry-After")
	public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
		// Before the password check: a throttled attempt never reaches bcrypt and learns nothing.
		rateLimits.check("login-per-email", EmailRateLimitKey.of(request.email()));
		UUID accountId = accounts.authenticate(request.email(), request.password());
		return withSession(accountId, refreshTokens.issue(accountId));
	}
```

`api/src/main/resources/application.yml` — under `musicboxd.rate-limit.policies`, after `demo`:

```yaml
      # MBD-23 (AD-10): anonymous auth endpoints are limited twice. *-per-email counts one account
      # (the email in the body, whichever IP sends it); *-per-ip stops one caller cycling emails.
      login-per-ip:
        capacity: 20
        refill-period: 15m
      login-per-email:
        capacity: 10
        refill-period: 15m
```

`api/src/test/resources/config/application.yml` — under `musicboxd:` (next to `auth:`):

```yaml
  rate-limit:
    policies:
      # Every MockMvc request comes from 127.0.0.1 and the context is shared across test classes,
      # so per-IP auth limits would trip on unrelated tests. AuthRateLimitTest sets its own numbers.
      login-per-ip:
        capacity: 100000
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew test --tests '*AuthRateLimitTest' --tests '*EmailRateLimitKeyTest'`
Expected: PASS.

Then the whole API suite, since every test class that logs in now goes through the new limits:
Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. A 429 in `AuthFlowTest`, `RefreshFlowTest`, `SecurityRulesTest` or `AdminRouteProtectionTest` means a test logs in with one email more than 10 times; give that test a lifted `login-per-email` in the test config the same way, with a comment naming it.

- [ ] **Step 8: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/accounts/EmailRateLimitKey.java api/src/main/java/com/musicboxd/api/accounts/AuthController.java api/src/main/resources/application.yml api/src/test/resources/config/application.yml api/src/test/java/com/musicboxd/api/accounts/EmailRateLimitKeyTest.java api/src/test/java/com/musicboxd/api/accounts/AuthRateLimitTest.java
git commit -m "Throttle login per email and per client IP (MBD-23)"
```

---

### Task 3: Registration limits, per email and per IP

**Files:**
- Modify: `api/src/main/java/com/musicboxd/api/accounts/AuthController.java` (`register`)
- Modify: `api/src/main/resources/application.yml`
- Modify: `api/src/test/resources/config/application.yml`
- Test: `api/src/test/java/com/musicboxd/api/accounts/AuthRateLimitTest.java`

**Interfaces:**
- Consumes: `RateLimits.check` (Task 1); `EmailRateLimitKey.of`, the `rateLimits` field in `AuthController`, and the `AuthRateLimitTest` helpers `freshIp()`, `register(email, username, ip)` (Task 2).
- Produces: policies `register-per-ip`, `register-per-email`.

- [ ] **Step 1: Write the failing tests**

Add to `AuthRateLimitTest` (in a `// --- registration ---` section, after the login tests), plus `import static org.assertj.core.api.Assertions.assertThat;`:

```java
	@Test
	void registrationIsThrottledPerEmail() throws Exception {
		String email = uniqueEmail();
		register(email, uniqueUsername(), freshIp()).andExpect(status().isCreated());
		register(email, uniqueUsername(), freshIp()).andExpect(status().isConflict());

		register(email, uniqueUsername(), freshIp())
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"));
	}

	@Test
	void registrationIsThrottledPerIpAndAThrottledOneCreatesNothing() throws Exception {
		String ip = freshIp();
		for (int i = 0; i < 3; i++) {
			register(uniqueEmail(), uniqueUsername(), ip).andExpect(status().isCreated());
		}

		String fourth = uniqueEmail();
		register(fourth, uniqueUsername(), ip).andExpect(status().isTooManyRequests());

		assertThat(mail.sentTo(fourth)).isEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM accounts.accounts WHERE email = :email")
			.param("email", fourth)
			.query(Long.class)
			.single()).isZero();
		register(uniqueEmail(), uniqueUsername(), freshIp()).andExpect(status().isCreated());
	}

	@Test
	void openApiDocumentsTheRegistrationThrottle() throws Exception {
		mockMvc.perform(get("/api/v1/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses['429']").exists());
	}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests '*AuthRateLimitTest'`
Expected: the three new tests FAIL (409 / 201 where 429 is expected; no `429` in the docs). The login tests still PASS.

- [ ] **Step 3: Throttle registration**

`AuthController.java` — replace the `register` method:

```java
	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	@RateLimited(policy = "register-per-ip")
	@ApiResponse(responseCode = "201", description = "Account and profile created")
	@ApiResponse(responseCode = "400", description = "Invalid email, password or username")
	@ApiResponse(responseCode = "409", description = "Email or username already taken (type urn:musicboxd:problem:email-taken or urn:musicboxd:problem:username-taken)")
	@ApiResponse(responseCode = "429", description = "Too many sign-ups for this email or from this caller; see Retry-After")
	public AccountView register(@Valid @RequestBody RegisterRequest request) {
		// Before anything is written: a throttled sign-up creates no account and sends no email.
		rateLimits.check("register-per-email", EmailRateLimitKey.of(request.email()));
		return accounts.register(request.email(), request.password(), request.username());
	}
```

`api/src/main/resources/application.yml` — after `login-per-email`:

```yaml
      # Every sign-up sends a verification email (SES cost), so per-IP is the tight one.
      register-per-ip:
        capacity: 10
        refill-period: 1h
      register-per-email:
        capacity: 3
        refill-period: 1h
```

`api/src/test/resources/config/application.yml` — under `rate-limit.policies`, after `login-per-ip`:

```yaml
      register-per-ip:
        capacity: 100000
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests '*AuthRateLimitTest'`
Expected: PASS.

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/accounts/AuthController.java api/src/main/resources/application.yml api/src/test/resources/config/application.yml api/src/test/java/com/musicboxd/api/accounts/AuthRateLimitTest.java
git commit -m "Throttle registration per email and per client IP (MBD-23)"
```

---

### Task 4: Verification email per email, the policy rename, and the runbook

**Files:**
- Modify: `api/src/main/java/com/musicboxd/api/accounts/AuthController.java` (`resendVerification`)
- Modify: `api/src/main/resources/application.yml`
- Test: `api/src/test/java/com/musicboxd/api/accounts/AuthRateLimitTest.java`
- Test: `api/src/test/java/com/musicboxd/api/ratelimit/RateLimitPoliciesTest.java`
- Create: `docs/runbooks/runbook-mbd-23-auth-rate-limits.md`
- Modify: `docs/runbooks/runbook-mbd-13-rate-limits.md`

**Interfaces:**
- Consumes: `RateLimits.check` (Task 1); `EmailRateLimitKey.of`, `AuthRateLimitTest.resend(email, ip)` / `register(...)` / `freshIp()` (Task 2); `RateLimitProperties.policies() → Map<String, RateLimitPolicy>` (MBD-13).
- Produces: policies `verification-email-per-ip` (renamed from `verification-email`) and `verification-email-per-email`. After this task the six auth policy names are final.

- [ ] **Step 1: Write the failing tests**

Add to `AuthRateLimitTest` (`// --- verification email ---` section):

```java
	@Test
	void verificationEmailIsThrottledPerEmailWhicheverIp() throws Exception {
		String email = uniqueEmail();
		register(email, uniqueUsername(), freshIp()).andExpect(status().isCreated()); // first email

		resend(email, freshIp()).andExpect(status().isAccepted());
		resend(email, freshIp()).andExpect(status().isAccepted());
		resend(email, freshIp())
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"));

		assertThat(mail.sentTo(email)).hasSize(3);
	}

	@Test
	void verificationEmailThrottleDoesNotRevealWhetherTheAccountExists() throws Exception {
		String nobody = uniqueEmail();
		resend(nobody, freshIp()).andExpect(status().isAccepted());
		resend(nobody, freshIp()).andExpect(status().isAccepted());
		resend(nobody, freshIp()).andExpect(status().isTooManyRequests());
	}

	@Test
	void verificationEmailIsStillThrottledPerIp() throws Exception {
		String ip = freshIp();
		for (int i = 0; i < 10; i++) {
			resend(uniqueEmail(), ip).andExpect(status().isAccepted());
		}
		resend(uniqueEmail(), ip).andExpect(status().isTooManyRequests());
	}
```

`RateLimitPoliciesTest.java` (runs on the real `application.yml`, through the shared test context; the test config only raises numbers, it adds no names that `application.yml` lacks):

```java
package com.musicboxd.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.musicboxd.api.TestcontainersConfiguration;

/** A policy a controller names but the config lacks is a 500 at request time; catch it here. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RateLimitPoliciesTest {

	@Autowired
	private RateLimitProperties properties;

	@Test
	void everyAuthPolicyIsConfigured() {
		assertThat(properties.policies()).containsKeys(
			"login-per-ip", "login-per-email",
			"register-per-ip", "register-per-email",
			"verification-email-per-ip", "verification-email-per-email");
		assertThat(properties.policies()).doesNotContainKey("verification-email");
	}
}
```

- [ ] **Step 2: Run them to verify they fail**

Run: `./gradlew test --tests '*AuthRateLimitTest' --tests '*RateLimitPoliciesTest'`
Expected: `verificationEmailIsThrottledPerEmailWhicheverIp` and `verificationEmailThrottleDoesNotRevealWhetherTheAccountExists` get 202 where 429 is expected. `verificationEmailIsStillThrottledPerIp` gets 429 already at the 4th request (the old `verification-email` policy, 3 per 15m, is not overridden by this class). `RateLimitPoliciesTest` lacks `verification-email-per-ip` and `verification-email-per-email`.

- [ ] **Step 3: Rename the IP policy and add the email check**

`AuthController.java` — replace the `resendVerification` method:

```java
	@PostMapping("/verification-email")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@RateLimited(policy = "verification-email-per-ip")
	@ApiResponse(responseCode = "202", description = "If an unverified account has this email, a new link was sent")
	@ApiResponse(responseCode = "400", description = "Invalid email")
	@ApiResponse(responseCode = "429",
			description = "Too many requests for this email or from this caller (same answer whether or not the account exists); see Retry-After")
	public void resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
		// Keyed on the address, not the account, so known and unknown emails are throttled alike.
		rateLimits.check("verification-email-per-email", EmailRateLimitKey.of(request.email()));
		verifications.resend(request.email());
	}
```

`api/src/main/resources/application.yml` — replace the `verification-email` entry and its comment with:

```yaml
      # MBD-18 resend. Per IP as before; per email bounds what one inbox can be sent from any IPs.
      verification-email-per-ip:
        capacity: 3
        refill-period: 15m
      verification-email-per-email:
        capacity: 5
        refill-period: 24h
```

Check nothing else names the old policy:
Run (repo root): `git grep -n '"verification-email"\|verification-email:' -- api`
Expected: no output.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew test --tests '*AuthRateLimitTest' --tests '*RateLimitPoliciesTest' --tests '*AuthFlowTest'`
Expected: PASS. `AuthFlowTest`'s MBD-18 resend test still relies on 3 per IP and now also spends 3 of the 5 per-email tokens; both fit.

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Write the runbook**

`docs/runbooks/runbook-mbd-23-auth-rate-limits.md`:

````markdown
# Runbook: MBD-23 auth rate limits (tunables and live verification)

Goal (acceptance criterion): **repeated login attempts, repeated registrations, and repeated verification-email
requests from one account are each throttled past their configured limit.**

Run this against the live host after CD (MBD-10) has deployed the MBD-23 merge. **[local]** commands run on your
machine (Git Bash); **[host]** commands run in an SSM session (`aws ssm start-session --target <INSTANCE_ID> --region us-east-1`).
Values: domain `musicboxd.com.br`, stack at `/opt/musicboxd`, env files in `/etc/musicboxd/`.

## Tunables

Each endpoint has two buckets: one per email in the body (whichever IP sends it) and one per client IP.
The email key is the normalized address (`strip` + lowercase), hashed. It is the same for known and unknown accounts.

| Policy | Capacity / refill | Endpoint |
| --- | --- | --- |
| `login-per-ip` | 20 / 15m | `POST /api/v1/auth/login` |
| `login-per-email` | 10 / 15m | same |
| `register-per-ip` | 10 / 1h | `POST /api/v1/auth/register` |
| `register-per-email` | 3 / 1h | same |
| `verification-email-per-ip` | 3 / 15m | `POST /api/v1/auth/verification-email` |
| `verification-email-per-email` | 5 / 24h | same |

Refill is continuous: with 10 per 15m, one attempt comes back every 90 s. Override on the host without a rebuild by
adding one line to `/etc/musicboxd/api.env`, then recreating the api. These policy names contain dashes, and a plain
environment variable cannot name a map key with a dash (`..._LOGINPEREMAIL_...` would create a new, unused policy
`loginperemail`), so use `SPRING_APPLICATION_JSON`, unquoted, with every override in that one line:
`SPRING_APPLICATION_JSON={"musicboxd":{"rate-limit":{"policies":{"login-per-email":{"capacity":20,"refill-period":"15m"}}}}}`
Check it took effect: `sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml exec api env | grep SPRING_APPLICATION_JSON`. **[host]**
`cd /opt/musicboxd && sudo docker compose --env-file /etc/musicboxd/stack.env -f docker-compose.prod.yml up -d api`
State is in memory: a restart or deploy refills every bucket.

Known trade-off: anyone who knows an address can keep its login throttled. After they stop, the owner waits at
most 90 s. If this is abused, raise `login-per-email` first.

## Verification

Use a throwaway address you control (`<EMAIL>`). The per-email limits are what this checks, so it is fine to
send everything from your own IP, but each section below spends your IP's tokens too: wait 15 minutes between
sections, or run them from different networks.

- [ ] **Login.** **[local]** Send 11 wrong-password logins for `<EMAIL>`:
  `for i in $(seq 11); do curl -sS -o /dev/null -w "%{http_code} " -H 'Content-Type: application/json' -d '{"email":"<EMAIL>","password":"wrong-password"}' https://musicboxd.com.br/api/v1/auth/login; done; echo`
  Expected: ten `401` then `429`. Then the right password also gets `429` with a `Retry-After` header:
  `curl -sS -i -H 'Content-Type: application/json' -d '{"email":"<EMAIL>","password":"<PASSWORD>"}' https://musicboxd.com.br/api/v1/auth/login | grep -iE '^HTTP|^retry-after'`
- [ ] **Registration.** **[local]** Send 4 sign-ups for a new `<EMAIL2>`, each with a fresh username:
  `for i in $(seq 4); do curl -sS -o /dev/null -w "%{http_code} " -H 'Content-Type: application/json' -d "{\"email\":\"<EMAIL2>\",\"password\":\"correct-horse\",\"username\":\"rl_test_$i\"}" https://musicboxd.com.br/api/v1/auth/register; done; echo`
  Expected: `201 409 409 429`. Exactly one verification email arrives at `<EMAIL2>`.
- [ ] **Verification email.** **[local]** For the unverified `<EMAIL2>`, send 6 resends, one every 5 minutes so the per-IP
  limit (3 / 15m) never trips:
  `curl -sS -o /dev/null -w "%{http_code}\n" -H 'Content-Type: application/json' -d '{"email":"<EMAIL2>"}' https://musicboxd.com.br/api/v1/auth/verification-email`
  Expected: five `202` then `429`. The inbox has at most 6 emails for `<EMAIL2>` in 24 h (1 sign-up + 5 resends).
- [ ] **Clean up.** Delete the test accounts if you made any by hand (account deletion is deferred, so via SQL on the host per `runbook-mbd-17-auth.md`).
````

`docs/runbooks/runbook-mbd-13-rate-limits.md` — in the tunables table, change the "Spring per-user policies" row's value cell to:
`` `demo`: 5 requests per `1m`; auth policies (login, register, verification email): see `runbook-mbd-23-auth-rate-limits.md` ``

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/accounts/AuthController.java api/src/main/resources/application.yml api/src/test/java/com/musicboxd/api/accounts/AuthRateLimitTest.java api/src/test/java/com/musicboxd/api/ratelimit/RateLimitPoliciesTest.java docs/runbooks/runbook-mbd-23-auth-rate-limits.md docs/runbooks/runbook-mbd-13-rate-limits.md
git commit -m "Throttle verification email per email, rename its per-IP policy, and add the MBD-23 runbook (MBD-23)"
```

---

### Task 5: SPA — say "too many attempts" on 429 for login and sign-up

**Files:**
- Modify: `web/src/pages/LoginPage.tsx`
- Modify: `web/src/pages/RegisterPage.tsx`
- Test: `web/src/pages/LoginPage.test.tsx`
- Test: `web/src/pages/RegisterPage.test.tsx`

**Interfaces:**
- Consumes: `ApiError.status` (`web/src/auth/authApi.ts`); test helpers `fakeApi`, `problem(status, type?)`, `renderApp` (`web/src/test/`); 429 from Tasks 2 and 3.
- Produces: nothing other tasks use.

- [ ] **Step 1: Write the failing tests**

`LoginPage.test.tsx` — add inside `describe('LoginPage', ...)`:

```tsx
  it('asks to wait on a 429 instead of blaming the credentials', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401), 'POST /api/v1/auth/login': () => problem(429) });
    renderApp('/entrar');

    await fillAndSubmit('ana@exemplo.com', 'correct-horse');

    expect(await screen.findByRole('alert')).toHaveTextContent('Muitas tentativas. Espere alguns minutos e tente de novo.');
    expect(screen.getByLabelText('Email')).not.toHaveAttribute('aria-invalid', 'true');
  });
```

`RegisterPage.test.tsx` — add inside `describe('RegisterPage', ...)`:

```tsx
  it('asks to wait on a 429', async () => {
    fakeApi({ 'POST /api/v1/auth/refresh': () => problem(401), 'POST /api/v1/auth/register': () => problem(429) });
    renderApp('/cadastro');

    await fill('ana_silva', 'ana@exemplo.com', 'correct-horse');

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Muitas tentativas de cadastro. Espere alguns minutos e tente de novo.',
    );
  });
```

- [ ] **Step 2: Run them to verify they fail**

Run (from `web/`): `npm test -- src/pages/LoginPage.test.tsx src/pages/RegisterPage.test.tsx`
Expected: the two new tests FAIL; the alert reads "Não foi possível entrar agora." / "Não foi possível criar a conta agora.".

- [ ] **Step 3: Implement**

`LoginPage.tsx`:

```tsx
type Failure = 'credentials' | 'rate-limited' | 'unavailable' | null;
```

In the `catch`, replace the `setFailure(...)` line:

```tsx
      if (error instanceof ApiError && error.status === 401) {
        setFailure('credentials');
      } else if (error instanceof ApiError && error.status === 429) {
        setFailure('rate-limited');
      } else {
        setFailure('unavailable');
      }
```

After the `credentials` alert:

```tsx
      {failure === 'rate-limited' && <Alert title="Muitas tentativas.">Espere alguns minutos e tente de novo.</Alert>}
```

`RegisterPage.tsx`:

```tsx
type Failure = 'rejected' | 'rate-limited' | 'unavailable' | null;
```

In the final `else` of the `catch`, replace the `setFailure(...)` line:

```tsx
        if (error instanceof ApiError && error.status === 400) {
          setFailure('rejected');
        } else if (error instanceof ApiError && error.status === 429) {
          setFailure('rate-limited');
        } else {
          setFailure('unavailable');
        }
```

After the `rejected` alert:

```tsx
      {failure === 'rate-limited' && (
        <Alert title="Muitas tentativas de cadastro.">Espere alguns minutos e tente de novo.</Alert>
      )}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run (from `web/`): `npm test`
Expected: PASS (all suites).

Then confirm the generated client still builds against the new OpenAPI (it is git-ignored; nothing to commit):
Run (from `api/`): `./gradlew generateOpenApiDocs`, then (from `web/`): `npm run build`
Expected: both succeed; `grep -c '429' src/api-client/types.gen.ts` is at least 3.

- [ ] **Step 5: Commit**

```bash
git add web/src/pages/LoginPage.tsx web/src/pages/LoginPage.test.tsx web/src/pages/RegisterPage.tsx web/src/pages/RegisterPage.test.tsx
git commit -m "Tell the user to wait when login or sign-up is rate limited (MBD-23)"
```

---

## After the last task

- [ ] Full suites green: `./gradlew test` (from `api/`) and `npm test` (from `web/`).
- [ ] Open the PR; after merge and deploy, run `docs/runbooks/runbook-mbd-23-auth-rate-limits.md` and tick the Jira ticket MBD-23.
