# MBD-19 Refresh Tokens (Opaque, Rotated, Revocable) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Login also issues an opaque refresh token in an HttpOnly cookie. `POST /api/v1/auth/refresh` trades it for a new access token and a new refresh token. Replaying a rotated-out token is rejected and revokes that login's whole token family, except inside a 10-second grace window where it still succeeds.

**Architecture:** Same opaque-token pattern as MBD-18's verification links: 32 random bytes, Base64url, only `SHA-256(raw)` in the database. Each login starts a *family* (`family_id`). Rotation marks the presented row `rotated_at` and inserts a successor in the same family, under a `SELECT ... FOR UPDATE` row lock so concurrent refreshes serialize. A rotated token presented again within the grace window gets a *sibling* successor, so storage stays hash-only. Presented after the window, it revokes the family.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring Security 7, OAuth2 resource server), Spring JDBC `JdbcClient`, Flyway per-module schema, PostgreSQL 18, JUnit 5 / MockMvc / AssertJ / Hamcrest, Testcontainers.

**Spec:** Jira MBD-19 / `_bmad-output/initiative-musicboxd/epic-contas-acesso/story-refresh-tokens-opaque-rotated-revocable.md`. Architecture: `_bmad-output/planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md` AD-8, and finding H4 in `reviews/review-adversary.md` (why the grace window exists). No separate design doc: the decisions below were taken as defaults so the story can ship fast.

**Branch:** if MBD-18 (`story/mbd-18-email-verification`) is merged, `git switch main && git pull && git switch -c story/mbd-19-refresh-tokens`. If not, branch from `story/mbd-18-email-verification`. Commit messages end with `(MBD-19)` and the `Co-Authored-By` trailer. Run Gradle from `api/` (`./gradlew` in Git Bash, `.\gradlew.bat` in PowerShell). Docker must be running for Testcontainers.

## Decisions (defaults chosen for this story)

| Question | Decision |
|---|---|
| Grace-window replay returns | A **new sibling** token in the same family (no raw token is ever stored). |
| Grace window | `10s`, inclusive (`now <= rotated_at + 10s` succeeds). |
| Refresh-token lifetime | `30d`, **sliding**: every successor gets `now + 30d`. No absolute family cap (later story if wanted). |
| Reuse after the window | Revoke **the whole family** (that login only; other devices keep working) and answer 401. |
| Cookie | `musicboxd_refresh`, `HttpOnly; Secure; SameSite=Lax; Path=/api/v1/auth/refresh; Max-Age=2592000`. |
| Logout (MBD-20, not here) | Will be `DELETE /api/v1/auth/refresh`, so the same cookie path reaches it. |
| CSRF | Stays disabled. `SameSite=Lax` stops cross-site POSTs from carrying the cookie (AD-8). |
| Stale bearer headers | Ignored on `/api/v1/auth/**`, so an SPA interceptor that attaches an expired JWT cannot turn a refresh into a 401. |
| Cleanup of old rows | Out of scope (rows are small; a sweep can come with MBD-25). |

## Global Constraints

- Only `SHA-256(raw)` is stored, as 64 lowercase hex chars. Tokens: 32 bytes from `SecureRandom`, Base64url without padding (shared with MBD-18 through `OpaqueTokens`).
- Config: `musicboxd.auth.refresh-token.ttl: 30d`, `musicboxd.auth.refresh-token.grace: 10s`. No new env vars, no deploy change.
- The access token stays in the JSON body (`TokenResponse` unchanged). The refresh token travels **only** in `Set-Cookie`, never in a body or a log line.
- Every refresh failure (missing, unknown, expired, revoked, reused) is the same 401 Problem Details with `type` `urn:musicboxd:problem:invalid-refresh-token`, and clears the cookie (`Max-Age=0`).
- Errors are RFC 9457 Problem Details. Routes under `/api/v1`, documented by springdoc (AD-7).
- Schema changes only through Flyway in `db/migration/accounts`. Repositories are package-private and use `JdbcClient`. Java sources use tabs.
- Out of scope: logout endpoint (MBD-20), SPA single-flight refresh and screens (MBD-22), rate limits (MBD-23), staff role claim (MBD-21).

## Review Focus

- **Reuse detection must survive its own exception.** `rotate` throws after revoking the family; a default `@Transactional` would roll the revocation back. It must commit. (Task 1: `replayAfterTheGraceWindowIsRejectedAndRevokesTheFamily` checks the successor is dead afterwards.)
- **Two tabs refreshing at once must not log the person out.** Concurrent calls with the same token both succeed. (Task 1: `concurrentRefreshesOfOneTokenBothSucceed`; Task 2: `twoRefreshesWithinTheGraceWindowBothSucceed`.)
- **One stolen-token incident must not log out every device.** Revocation is per family. (Task 1: `reuseRevokesOnlyThatFamily`.)
- **An expired access token sent along with the refresh call.** SPA interceptors add `Authorization` to every request; refresh must still answer 200. (Task 2: `aStaleBearerHeaderDoesNotBlockRefresh`.)
- **Hostile or missing cookie.** No cookie, empty value, garbage: 401 Problem Details, never 500, and the cookie is cleared. (Task 2: `missingOrUnknownCookieIs401AndClearsIt`.)

---

## File Structure

**Domain (Task 1)**: `api/src/main/java/com/musicboxd/api/accounts/`
- Create `OpaqueTokens.java`: generate a raw token, hash it. Shared with MBD-18.
- Modify `EmailVerificationService.java`: use `OpaqueTokens` (pure refactor).
- Create `RefreshTokenProperties.java`, `RefreshTokenRepository.java`, `RefreshTokenService.java`, `RefreshCookies.java`, `InvalidRefreshTokenException.java`.
- Modify `AccountsConfig.java` (register the properties), `api/src/main/resources/application.yml`.
- Create `api/src/main/resources/db/migration/accounts/V3__refresh_tokens.sql`.
- Create `api/src/test/java/com/musicboxd/api/accounts/RefreshTokenServiceTest.java`.

**HTTP (Task 2)**
- Modify `accounts/AuthController.java`, `security/SecurityConfig.java`.
- Create `api/src/test/java/com/musicboxd/api/accounts/RefreshFlowTest.java`. Modify `security/SecurityRulesTest.java`.

**Ship check (Task 3)**: no code; full suite plus the manual concurrent-refresh check the story's high-risk note asks for.

---

### Task 1: Refresh-token domain (issue, rotate, grace, reuse revocation)

**Files:**
- Create: `api/src/main/java/com/musicboxd/api/accounts/OpaqueTokens.java`
- Modify: `api/src/main/java/com/musicboxd/api/accounts/EmailVerificationService.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/RefreshTokenProperties.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/RefreshTokenRepository.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/RefreshTokenService.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/RefreshCookies.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/InvalidRefreshTokenException.java`
- Modify: `api/src/main/java/com/musicboxd/api/accounts/AccountsConfig.java`
- Modify: `api/src/main/resources/application.yml`
- Create: `api/src/main/resources/db/migration/accounts/V3__refresh_tokens.sql`
- Test: `api/src/test/java/com/musicboxd/api/accounts/RefreshTokenServiceTest.java`

**Interfaces:**
- Consumes: `AccountService.register(String email, String password, String username) -> AccountView` (tests only, to get a real account id for the FK); `Clock` bean from `security.JwtConfig`.
- Produces:
  - `OpaqueTokens.generate() -> String`, `OpaqueTokens.hash(String raw) -> String` (package-private, static).
  - `RefreshTokenService.issue(UUID accountId) -> String` (raw token, new family).
  - `RefreshTokenService.rotate(String rawToken) -> RefreshTokenService.Rotation` where `record Rotation(UUID accountId, String refreshToken)`; throws `InvalidRefreshTokenException`.
  - `RefreshTokenProperties(Duration ttl, Duration grace)`.
  - `RefreshCookies.NAME = "musicboxd_refresh"`, `RefreshCookies.PATH = "/api/v1/auth/refresh"`, `RefreshCookies.issue(String raw, Duration maxAge) -> ResponseCookie`, `RefreshCookies.clear() -> ResponseCookie`.
  - `InvalidRefreshTokenException.TYPE = urn:musicboxd:problem:invalid-refresh-token`.

- [ ] **Step 1: Write the failing tests**

`api/src/test/java/com/musicboxd/api/accounts/RefreshTokenServiceTest.java`:

```java
package com.musicboxd.api.accounts;

import static com.musicboxd.api.accounts.AccountServiceTest.uniqueEmail;
import static com.musicboxd.api.accounts.AccountServiceTest.uniqueUsername;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.musicboxd.api.TestcontainersConfiguration;

/** MBD-19 domain rules. NOT @Transactional: rotation must commit for real, and the concurrency test needs two transactions. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RefreshTokenServiceTest {

	@Autowired
	private AccountService accounts;

	@Autowired
	private RefreshTokenService refreshTokens;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void onlyTheSha256IsStoredAndItExpiresInThirtyDays() throws Exception {
		UUID account = newAccount();
		String raw = refreshTokens.issue(account);

		String stored = jdbc.sql("SELECT token_hash FROM accounts.refresh_tokens WHERE account_id = :id")
			.param("id", account).query(String.class).single();
		String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(UTF_8)));
		assertThat(stored).isEqualTo(expected).hasSize(64).isNotEqualTo(raw);

		OffsetDateTime expiresAt = jdbc.sql("SELECT expires_at FROM accounts.refresh_tokens WHERE account_id = :id")
			.param("id", account).query(OffsetDateTime.class).single();
		assertThat(Duration.between(OffsetDateTime.now(), expiresAt))
			.isBetween(Duration.ofDays(30).minusMinutes(1), Duration.ofDays(30));
	}

	@Test
	void rotationReturnsANewTokenForTheSameAccount() {
		UUID account = newAccount();
		String first = refreshTokens.issue(account);

		var rotation = refreshTokens.rotate(first);

		assertThat(rotation.accountId()).isEqualTo(account);
		assertThat(rotation.refreshToken()).isNotEqualTo(first);
		assertThat(refreshTokens.rotate(rotation.refreshToken()).accountId()).isEqualTo(account);
	}

	@Test
	void replayWithinTheGraceWindowSucceedsWithASibling() {
		UUID account = newAccount();
		String first = refreshTokens.issue(account);

		var a = refreshTokens.rotate(first);
		var b = refreshTokens.rotate(first); // a second tab, or a retry after a lost response
		ageRotation(first, Duration.ofSeconds(9));
		var c = refreshTokens.rotate(first);

		assertThat(b.accountId()).isEqualTo(account);
		assertThat(c.accountId()).isEqualTo(account);
		assertThat(a.refreshToken()).isNotEqualTo(b.refreshToken()).isNotEqualTo(c.refreshToken());
		refreshTokens.rotate(a.refreshToken());
		refreshTokens.rotate(b.refreshToken());
		refreshTokens.rotate(c.refreshToken());
	}

	@Test
	void replayAfterTheGraceWindowIsRejectedAndRevokesTheFamily() {
		UUID account = newAccount();
		String first = refreshTokens.issue(account);
		var next = refreshTokens.rotate(first);
		ageRotation(first, Duration.ofSeconds(11));

		assertThatThrownBy(() -> refreshTokens.rotate(first)).isInstanceOf(InvalidRefreshTokenException.class);
		// The revocation committed despite the exception: the live successor is dead too.
		assertThatThrownBy(() -> refreshTokens.rotate(next.refreshToken()))
			.isInstanceOf(InvalidRefreshTokenException.class);
	}

	@Test
	void reuseRevokesOnlyThatFamily() {
		UUID account = newAccount();
		String laptop = refreshTokens.issue(account);
		String phone = refreshTokens.issue(account);
		refreshTokens.rotate(laptop);
		ageRotation(laptop, Duration.ofSeconds(11));

		assertThatThrownBy(() -> refreshTokens.rotate(laptop)).isInstanceOf(InvalidRefreshTokenException.class);
		assertThat(refreshTokens.rotate(phone).accountId()).isEqualTo(account);
	}

	@Test
	void expiredTokenIsRejected() {
		String raw = refreshTokens.issue(newAccount());
		jdbc.sql("UPDATE accounts.refresh_tokens SET expires_at = :past WHERE token_hash = :hash")
			.param("past", OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
			.param("hash", OpaqueTokens.hash(raw)).update();

		assertThatThrownBy(() -> refreshTokens.rotate(raw)).isInstanceOf(InvalidRefreshTokenException.class);
	}

	@Test
	void unknownTokenIsRejected() {
		assertThatThrownBy(() -> refreshTokens.rotate("not-a-real-token"))
			.isInstanceOf(InvalidRefreshTokenException.class);
	}

	@Test
	void concurrentRefreshesOfOneTokenBothSucceed() throws Exception {
		String first = refreshTokens.issue(newAccount());
		var start = new CountDownLatch(1);
		Callable<RefreshTokenService.Rotation> refresh = () -> {
			start.await();
			return refreshTokens.rotate(first);
		};
		try (var pool = Executors.newFixedThreadPool(2)) {
			var a = pool.submit(refresh);
			var b = pool.submit(refresh);
			start.countDown();
			assertThat(a.get(10, SECONDS).refreshToken()).isNotEqualTo(b.get(10, SECONDS).refreshToken());
		}
	}

	private UUID newAccount() {
		return accounts.register(uniqueEmail(), "correct-horse", uniqueUsername()).id();
	}

	/** Pretends the token was rotated {@code ago} in the past. */
	private void ageRotation(String raw, Duration ago) {
		jdbc.sql("UPDATE accounts.refresh_tokens SET rotated_at = :at WHERE token_hash = :hash")
			.param("at", OffsetDateTime.now(ZoneOffset.UTC).minus(ago))
			.param("hash", OpaqueTokens.hash(raw)).update();
	}
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.musicboxd.api.accounts.RefreshTokenServiceTest"`
Expected: compilation FAIL (`RefreshTokenService`, `OpaqueTokens`, `InvalidRefreshTokenException` not found).

- [ ] **Step 3: Extract `OpaqueTokens` and use it in `EmailVerificationService`**

`api/src/main/java/com/musicboxd/api/accounts/OpaqueTokens.java`:

```java
package com.musicboxd.api.accounts;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Random bearer secrets that are stored only as SHA-256 (AD-8): verification links and refresh tokens.
 * A fast hash is safe because the input has 256 bits of entropy, and it must be deterministic to look rows up.
 */
final class OpaqueTokens {

	private static final int TOKEN_BYTES = 32;
	private static final SecureRandom RANDOM = new SecureRandom();

	private OpaqueTokens() {
	}

	static String generate() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String rawToken) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by every JVM", e);
		}
	}
}
```

In `EmailVerificationService.java`:
- Delete the `TOKEN_BYTES` constant, the `random` field, the `hash` method, and the now-unused imports (`UTF_8`, `MessageDigest`, `NoSuchAlgorithmException`, `SecureRandom`, `Base64`, `HexFormat`).
- Replace the body of `issue` with:

```java
		String raw = OpaqueTokens.generate();
		tokens.insert(OpaqueTokens.hash(raw), accountId, clock.instant().plus(props.ttl()));
		events.publishEvent(new VerificationEmailRequested(accountId, email, raw));
```

- In `verify`, replace `String hash = hash(rawToken);` with `String hash = OpaqueTokens.hash(rawToken);`.

- [ ] **Step 4: Write the migration**

`api/src/main/resources/db/migration/accounts/V3__refresh_tokens.sql`:

```sql
-- MBD-19: opaque refresh tokens (AD-8). Only SHA-256(token) is stored; the raw token lives in the cookie.
-- Each login starts a family; each refresh adds a row to it. Reusing a rotated-out token after the
-- grace window revokes the whole family.
CREATE TABLE accounts.refresh_tokens (
	token_hash  text        PRIMARY KEY,
	family_id   uuid        NOT NULL,
	account_id  uuid        NOT NULL REFERENCES accounts.accounts (id),
	expires_at  timestamptz NOT NULL,
	rotated_at  timestamptz,
	revoked_at  timestamptz,
	created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX refresh_tokens_family_idx ON accounts.refresh_tokens (family_id);
CREATE INDEX refresh_tokens_account_idx ON accounts.refresh_tokens (account_id);
```

- [ ] **Step 5: Add properties and config**

`api/src/main/java/com/musicboxd/api/accounts/RefreshTokenProperties.java`:

```java
package com.musicboxd.api.accounts;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param ttl refresh-token lifetime; sliding, each rotation starts a new one
 * @param grace how long a rotated-out token still refreshes (parallel tabs, lost responses; AD-8)
 */
@ConfigurationProperties("musicboxd.auth.refresh-token")
public record RefreshTokenProperties(@DefaultValue("30d") Duration ttl, @DefaultValue("10s") Duration grace) {
}
```

In `AccountsConfig.java`, change the annotation to:

```java
@EnableConfigurationProperties({ VerificationProperties.class, RefreshTokenProperties.class })
```

In `api/src/main/resources/application.yml`, under `musicboxd.auth`, after `access-token-ttl: 15m`, add:

```yaml
    # MBD-19: opaque, rotated on every use; a rotated-out token still works for `grace` (AD-8).
    refresh-token:
      ttl: 30d
      grace: 10s
```

- [ ] **Step 6: Write the repository**

`api/src/main/java/com/musicboxd/api/accounts/RefreshTokenRepository.java`:

```java
package com.musicboxd.api.accounts;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RefreshTokenRepository {

	record StoredRefreshToken(UUID familyId, UUID accountId, Instant expiresAt, Instant rotatedAt, Instant revokedAt) {
	}

	private final JdbcClient jdbc;

	RefreshTokenRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(String tokenHash, UUID familyId, UUID accountId, Instant expiresAt) {
		jdbc.sql("""
				INSERT INTO accounts.refresh_tokens (token_hash, family_id, account_id, expires_at)
				VALUES (:hash, :family, :account, :expires)""")
			.param("hash", tokenHash)
			.param("family", familyId)
			.param("account", accountId)
			.param("expires", utc(expiresAt))
			.update();
	}

	/** Locks the row so concurrent refreshes with one token serialize; the second sees the first's rotation. */
	Optional<StoredRefreshToken> findForUpdate(String tokenHash) {
		return jdbc.sql("""
				SELECT family_id, account_id, expires_at, rotated_at, revoked_at FROM accounts.refresh_tokens
				WHERE token_hash = :hash FOR UPDATE""")
			.param("hash", tokenHash)
			.query((rs, n) -> new StoredRefreshToken(
					rs.getObject("family_id", UUID.class),
					rs.getObject("account_id", UUID.class),
					instant(rs, "expires_at"),
					instant(rs, "rotated_at"),
					instant(rs, "revoked_at")))
			.optional();
	}

	/** Keeps the first rotation time, so the grace window never slides. */
	void markRotated(String tokenHash, Instant at) {
		jdbc.sql("UPDATE accounts.refresh_tokens SET rotated_at = :at WHERE token_hash = :hash AND rotated_at IS NULL")
			.param("hash", tokenHash)
			.param("at", utc(at))
			.update();
	}

	void revokeFamily(UUID familyId, Instant at) {
		jdbc.sql("UPDATE accounts.refresh_tokens SET revoked_at = :at WHERE family_id = :family AND revoked_at IS NULL")
			.param("family", familyId)
			.param("at", utc(at))
			.update();
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		return Optional.ofNullable(rs.getObject(column, OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null);
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
```

- [ ] **Step 7: Write the cookie helper and the exception**

`api/src/main/java/com/musicboxd/api/accounts/RefreshCookies.java`:

```java
package com.musicboxd.api.accounts;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

/**
 * The refresh cookie (AD-8): HttpOnly so scripts cannot read it, SameSite=Lax so cross-site POSTs do not
 * carry it, and sent only to the refresh path (MBD-20's logout is DELETE on the same path).
 */
final class RefreshCookies {

	static final String NAME = "musicboxd_refresh";
	static final String PATH = "/api/v1/auth/refresh";

	private RefreshCookies() {
	}

	static ResponseCookie issue(String rawToken, Duration maxAge) {
		return base(rawToken).maxAge(maxAge).build();
	}

	static ResponseCookie clear() {
		return base("").maxAge(0).build();
	}

	private static ResponseCookie.ResponseCookieBuilder base(String value) {
		return ResponseCookie.from(NAME, value).httpOnly(true).secure(true).sameSite("Lax").path(PATH);
	}
}
```

`api/src/main/java/com/musicboxd/api/accounts/InvalidRefreshTokenException.java`:

```java
package com.musicboxd.api.accounts;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Missing, unknown, expired, revoked or reused refresh token: one answer, so the response reveals nothing.
 * Clears the cookie so the browser stops sending a dead token. The SPA (MBD-22) sends the person to login.
 */
public class InvalidRefreshTokenException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:invalid-refresh-token");

	public InvalidRefreshTokenException() {
		super(HttpStatus.UNAUTHORIZED, problem(), null);
		getHeaders().add(HttpHeaders.SET_COOKIE, RefreshCookies.clear().toString());
	}

	private static ProblemDetail problem() {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Your session has ended; log in again");
		problem.setType(TYPE);
		problem.setTitle("Session expired");
		return problem;
	}
}
```

- [ ] **Step 8: Write the service**

`api/src/main/java/com/musicboxd/api/accounts/RefreshTokenService.java`:

```java
package com.musicboxd.api.accounts;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opaque refresh tokens (MBD-19, AD-8): hashed at rest, rotated on every use, reuse revokes the login's family. */
@Service
class RefreshTokenService {

	record Rotation(UUID accountId, String refreshToken) {
	}

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

	private final RefreshTokenRepository tokens;
	private final RefreshTokenProperties props;
	private final Clock clock;

	RefreshTokenService(RefreshTokenRepository tokens, RefreshTokenProperties props, Clock clock) {
		this.tokens = tokens;
		this.props = props;
		this.clock = clock;
	}

	/** Starts a new family: one per login, so revoking it logs out only that device. */
	@Transactional
	public String issue(UUID accountId) {
		return insertSuccessor(UUID.randomUUID(), accountId, clock.instant());
	}

	/**
	 * noRollbackFor: reuse detection revokes the family and then throws; a rollback would undo the revocation.
	 */
	@Transactional(noRollbackFor = InvalidRefreshTokenException.class)
	public Rotation rotate(String rawToken) {
		String hash = OpaqueTokens.hash(rawToken);
		var token = tokens.findForUpdate(hash).orElseThrow(InvalidRefreshTokenException::new);
		Instant now = clock.instant();
		if (token.revokedAt() != null || !now.isBefore(token.expiresAt())) {
			throw new InvalidRefreshTokenException();
		}
		if (token.rotatedAt() == null) {
			tokens.markRotated(hash, now);
		}
		else if (now.isAfter(token.rotatedAt().plus(props.grace()))) {
			tokens.revokeFamily(token.familyId(), now);
			log.warn("Refresh token reuse for account {}: revoked family {}", token.accountId(), token.familyId());
			throw new InvalidRefreshTokenException();
		}
		// Otherwise a parallel tab or a retried lost response, inside the grace window: it gets a sibling.
		return new Rotation(token.accountId(), insertSuccessor(token.familyId(), token.accountId(), now));
	}

	private String insertSuccessor(UUID familyId, UUID accountId, Instant now) {
		String raw = OpaqueTokens.generate();
		tokens.insert(OpaqueTokens.hash(raw), familyId, accountId, now.plus(props.ttl()));
		return raw;
	}
}
```

- [ ] **Step 9: Run the accounts tests**

Run: `./gradlew test --tests "com.musicboxd.api.accounts.*"`
Expected: PASS, including every `EmailVerificationServiceTest` test (the `OpaqueTokens` refactor changes no behavior).

- [ ] **Step 10: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/accounts api/src/main/resources/application.yml \
  api/src/main/resources/db/migration/accounts/V3__refresh_tokens.sql \
  api/src/test/java/com/musicboxd/api/accounts/RefreshTokenServiceTest.java
git commit -m "Add opaque refresh tokens rotated per use, with a grace window and family revocation on reuse (MBD-19)"
```

---

### Task 2: Login sets the cookie, `POST /api/v1/auth/refresh`

**Files:**
- Modify: `api/src/main/java/com/musicboxd/api/accounts/AuthController.java`
- Modify: `api/src/main/java/com/musicboxd/api/security/SecurityConfig.java`
- Create: `api/src/test/java/com/musicboxd/api/accounts/RefreshFlowTest.java`
- Modify: `api/src/test/java/com/musicboxd/api/security/SecurityRulesTest.java`

**Interfaces:**
- Consumes (Task 1): `RefreshTokenService.issue(UUID) -> String`, `RefreshTokenService.rotate(String) -> Rotation(UUID accountId, String refreshToken)`, `RefreshTokenProperties.ttl()`, `RefreshCookies.NAME`, `RefreshCookies.issue(String, Duration)`, `InvalidRefreshTokenException` (+ `TYPE`), `OpaqueTokens.hash(String)` (tests). Existing: `TokenService.issue(UUID) -> AccessToken(value, expiresInSeconds)`, `RecordingMailSender.verificationLink(String email)`.
- Produces: `POST /api/v1/auth/login` → 200 `TokenResponse` body + `Set-Cookie: musicboxd_refresh=...`. `POST /api/v1/auth/refresh` (cookie in) → 200 `TokenResponse` + rotated `Set-Cookie`, or 401 Problem Details + cleared cookie.

- [ ] **Step 1: Write the failing HTTP tests**

`api/src/test/java/com/musicboxd/api/accounts/RefreshFlowTest.java`:

```java
package com.musicboxd.api.accounts;

import static com.musicboxd.api.accounts.AccountServiceTest.uniqueEmail;
import static com.musicboxd.api.accounts.AccountServiceTest.uniqueUsername;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.mail.MailTestConfiguration;
import com.musicboxd.api.mail.RecordingMailSender;

import jakarta.servlet.http.Cookie;

/** MBD-19 acceptance, over HTTP: login sets the cookie, refresh rotates it, replay is rejected. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfiguration.class })
class RefreshFlowTest {

	private static final String PASSWORD = "correct-horse";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecordingMailSender mail;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void loginSetsAHardenedRefreshCookie() throws Exception {
		login(verifiedAccount())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isString())
			.andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
					containsString(RefreshCookies.NAME + "="),
					containsString("Path=/api/v1/auth/refresh"),
					containsString("Max-Age=2592000"),
					containsString("Secure"),
					containsString("HttpOnly"),
					containsString("SameSite=Lax"))));
	}

	@Test
	void refreshIssuesAWorkingAccessTokenAndRotatesTheCookie() throws Exception {
		String first = refreshCookie(login(verifiedAccount()).andReturn());

		MvcResult result = refresh(first)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andReturn();
		String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
		assertThat(refreshCookie(result)).isNotEqualTo(first);

		mockMvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk());
	}

	@Test
	void replayAfterRotationIsRejectedRevokesTheSessionAndClearsTheCookie() throws Exception {
		String first = refreshCookie(login(verifiedAccount()).andReturn());
		String second = refreshCookie(refresh(first).andReturn());
		ageRotation(first, Duration.ofSeconds(11));

		refresh(first)
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value(InvalidRefreshTokenException.TYPE.toString()))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
		refresh(second).andExpect(status().isUnauthorized());
	}

	@Test
	void twoRefreshesWithinTheGraceWindowBothSucceed() throws Exception {
		String first = refreshCookie(login(verifiedAccount()).andReturn());

		String a = refreshCookie(refresh(first).andExpect(status().isOk()).andReturn());
		String b = refreshCookie(refresh(first).andExpect(status().isOk()).andReturn());

		refresh(a).andExpect(status().isOk());
		refresh(b).andExpect(status().isOk());
	}

	@Test
	void missingOrUnknownCookieIs401AndClearsIt() throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.type").value(InvalidRefreshTokenException.TYPE.toString()))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
		refresh("").andExpect(status().isUnauthorized());
		refresh("not-a-real-token").andExpect(status().isUnauthorized());
		refresh("x".repeat(4_000)).andExpect(status().isUnauthorized());
	}

	@Test
	void aStaleBearerHeaderDoesNotBlockRefresh() throws Exception {
		String first = refreshCookie(login(verifiedAccount()).andReturn());

		mockMvc.perform(withCookie(post("/api/v1/auth/refresh"), first).header("Authorization", "Bearer not-a-jwt"))
			.andExpect(status().isOk());
	}

	private String verifiedAccount() throws Exception {
		String email = uniqueEmail();
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"%s","password":"%s","username":"%s"}""".formatted(email, PASSWORD, uniqueUsername())))
			.andExpect(status().isCreated());
		mockMvc.perform(get(URI.create(mail.verificationLink(email)))).andExpect(status().isOk());
		return email;
	}

	private ResultActions login(String email) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"%s","password":"%s"}""".formatted(email, PASSWORD)));
	}

	private ResultActions refresh(String cookieValue) throws Exception {
		return mockMvc.perform(withCookie(post("/api/v1/auth/refresh"), cookieValue));
	}

	private static MockHttpServletRequestBuilder withCookie(MockHttpServletRequestBuilder request, String value) {
		return request.cookie(new Cookie(RefreshCookies.NAME, value));
	}

	private static String refreshCookie(MvcResult result) {
		String prefix = RefreshCookies.NAME + "=";
		return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
			.filter(h -> h.startsWith(prefix))
			.map(h -> h.substring(prefix.length(), h.indexOf(';')))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no " + RefreshCookies.NAME + " cookie set"));
	}

	private void ageRotation(String raw, Duration ago) {
		jdbc.sql("UPDATE accounts.refresh_tokens SET rotated_at = :at WHERE token_hash = :hash")
			.param("at", OffsetDateTime.now(ZoneOffset.UTC).minus(ago))
			.param("hash", OpaqueTokens.hash(raw)).update();
	}
}
```

In `SecurityRulesTest.contractListsAuthRoutesAndTheBearerScheme`, after the `verification-email` line, add:

```java
			.andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post.responses['200']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post.responses['401']").exists())
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.musicboxd.api.accounts.RefreshFlowTest" --tests "com.musicboxd.api.security.SecurityRulesTest"`
Expected: FAIL. Login sets no `Set-Cookie` header, `/api/v1/auth/refresh` answers 401 from the security filter (no route permitted) with no `type`, and the contract has no refresh path.

- [ ] **Step 3: Wire login and refresh in `AuthController`**

Add imports:

```java
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
```

Add fields and change the constructor:

```java
	private final AccountService accounts;
	private final TokenService tokens;
	private final RefreshTokenService refreshTokens;
	private final RefreshTokenProperties refreshProps;
	private final EmailVerificationService verifications;

	AuthController(AccountService accounts, TokenService tokens, RefreshTokenService refreshTokens,
			RefreshTokenProperties refreshProps, EmailVerificationService verifications) {
		this.accounts = accounts;
		this.tokens = tokens;
		this.refreshTokens = refreshTokens;
		this.refreshProps = refreshProps;
		this.verifications = verifications;
	}
```

Replace the `login` method and add `refresh` plus a helper:

```java
	@PostMapping("/login")
	@ApiResponse(responseCode = "200",
			description = "A short-lived bearer access token; the refresh token is set as an HttpOnly cookie")
	@ApiResponse(responseCode = "401", description = "Unknown email or wrong password (indistinguishable)")
	@ApiResponse(responseCode = "403",
			description = "Right password, email not verified yet (type urn:musicboxd:problem:email-not-verified)")
	public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
		UUID accountId = accounts.authenticate(request.email(), request.password());
		return withSession(accountId, refreshTokens.issue(accountId));
	}

	/** The browser sends the cookie on its own; the SPA calls this when an access token expires or on page load. */
	@PostMapping("/refresh")
	@ApiResponse(responseCode = "200", description = "A new access token; the refresh cookie is rotated")
	@ApiResponse(responseCode = "401",
			description = "Missing, expired, revoked or reused refresh token (type urn:musicboxd:problem:invalid-refresh-token)")
	public ResponseEntity<TokenResponse> refresh(
			@CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken) {
		if (refreshToken == null || refreshToken.isBlank()) {
			throw new InvalidRefreshTokenException();
		}
		var rotation = refreshTokens.rotate(refreshToken);
		return withSession(rotation.accountId(), rotation.refreshToken());
	}

	private ResponseEntity<TokenResponse> withSession(UUID accountId, String refreshToken) {
		var access = tokens.issue(accountId);
		return ResponseEntity.ok()
			.header(HttpHeaders.SET_COOKIE, RefreshCookies.issue(refreshToken, refreshProps.ttl()).toString())
			.body(new TokenResponse(access.value(), "Bearer", access.expiresInSeconds()));
	}
```

- [ ] **Step 4: Open the route and ignore bearer headers on auth endpoints in `SecurityConfig`**

Add imports:

```java
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
```

Replace the CSRF comment:

```java
			// Bearer tokens are not cookie-borne, so CSRF does not apply to them. The one cookie, MBD-19's
			// refresh token, is SameSite=Lax (no cross-site POSTs) and scoped to /api/v1/auth/refresh (AD-8).
			.csrf(AbstractHttpConfigurer::disable)
```

Add `/api/v1/auth/refresh` to the public POSTs:

```java
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
						"/api/v1/auth/verification-email", "/api/v1/auth/refresh").permitAll()
```

Change the resource-server line to use the resolver:

```java
			.oauth2ResourceServer(oauth -> oauth
				.bearerTokenResolver(bearerTokenResolver())
				.jwt(Customizer.withDefaults())
				.authenticationEntryPoint(entryPoint));
```

Add the method to the class:

```java
	/**
	 * Auth endpoints are public and never read a bearer token. Without this, an expired JWT that an SPA
	 * interceptor attaches to every call would make the refresh itself fail with 401.
	 */
	private static BearerTokenResolver bearerTokenResolver() {
		var defaults = new DefaultBearerTokenResolver();
		return request -> request.getRequestURI().startsWith("/api/v1/auth/") ? null : defaults.resolve(request);
	}
```

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew test`
Expected: PASS, including `AuthFlowTest` (login still returns the same JSON body) and `OpenApiExportBootTest`.

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/accounts/AuthController.java \
  api/src/main/java/com/musicboxd/api/security/SecurityConfig.java \
  api/src/test/java/com/musicboxd/api/accounts/RefreshFlowTest.java \
  api/src/test/java/com/musicboxd/api/security/SecurityRulesTest.java
git commit -m "Set the refresh cookie at login and add the refresh endpoint (MBD-19)"
```

---

### Task 3: Ship check (the story's high-risk note)

No code. The story asks a person to fire concurrent refreshes against a running stack and confirm there is no false logout and no hole.

- [ ] **Step 1: Start the dev stack**

Run from the repo root: `docker compose -f deploy/docker-compose.yml up --build -d`

- [ ] **Step 2: Register, verify, log in, and capture the cookie (Git Bash)**

```bash
BASE=http://localhost
EMAIL="mbd19-$RANDOM@example.com"
curl -s -H 'content-type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"correct-horse\",\"username\":\"mbd19_$RANDOM\"}" $BASE/api/v1/auth/register
# Dev logs the email instead of sending it. Copy the link from:
docker compose -f deploy/docker-compose.yml logs api | grep -o "http[^ ]*verify?token=[^ ]*" | tail -1
curl -s "<paste the link>"
R1=$(curl -s -D - -o /dev/null -H 'content-type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"correct-horse\"}" $BASE/api/v1/auth/login \
  | grep -o 'musicboxd_refresh=[^;]*' | cut -d= -f2)
echo "$R1"
```

Expected: a 43-character token. The cookie is passed with `-H "Cookie: ..."` below because curl will not send a `Secure` cookie from a jar over plain http.

- [ ] **Step 3: Fire five concurrent refreshes with the same token**

```bash
for i in 1 2 3 4 5; do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST -H "Cookie: musicboxd_refresh=$R1" $BASE/api/v1/auth/refresh &
done; wait
```

Expected: five `200`s (no false logout).

- [ ] **Step 4: Wait past the grace window and replay**

```bash
sleep 12
curl -s -i -X POST -H "Cookie: musicboxd_refresh=$R1" $BASE/api/v1/auth/refresh | head -20
docker compose -f deploy/docker-compose.yml logs api | grep "Refresh token reuse"
```

Expected: `401`, `"type":"urn:musicboxd:problem:invalid-refresh-token"`, a `Set-Cookie` with `Max-Age=0`, and one `Refresh token reuse ... revoked family` warning (no hole). Any successor issued in Step 3 is now also rejected.

- [ ] **Step 5: Stop the stack, push, open the PR**

```bash
docker compose -f deploy/docker-compose.yml down
git push -u origin story/mbd-19-refresh-tokens
```

Open the PR against `main` (or against `story/mbd-18-email-verification` if MBD-18 is not merged yet). In the PR description, list the decisions table above, so the reviewer sees the grace-window and lifetime choices.
