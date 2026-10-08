# MBD-21 STAFF Role Claim and Admin Route Protection — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every access token carries a `role` claim (`USER` by default, `STAFF` when assigned). `/api/v1/admin/**` answers 403 to a USER and lets a STAFF through; every other write needs a USER or STAFF token; public GETs still need none. The first STAFF account is promoted by a Flyway migration from an environment variable.

**Architecture:** The role is a column on `accounts.accounts` (`V4`), read from the database **every time an access token is issued** (login and refresh), so a role change takes effect at the next access-token expiry (AD-8) with no extra machinery. `TokenService` writes it as the `role` claim; Spring Security's `JwtGrantedAuthoritiesConverter` turns it into `ROLE_USER` / `ROLE_STAFF`, and `SecurityConfig` matches `/api/v1/admin/**` with `hasRole("STAFF")` **before** any other rule. A 403 is RFC 9457 Problem Details, like the existing 401. The bootstrap is a repeatable migration (`R__`) that re-runs on every start (`${flyway:timestamp}`) and promotes the email in `MUSICBOXD_BOOTSTRAP_STAFF_EMAIL`, passed in as a Flyway placeholder.

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring Security 7, OAuth2 resource server), Flyway 12.4 (one instance per module schema), Spring JDBC `JdbcClient`, PostgreSQL 18, JUnit 5 (+ `@ParameterizedTest`) / MockMvc / AssertJ, Testcontainers.

**Spec:** Jira MBD-21 / `_bmad-output/initiative-musicboxd/epic-contas-acesso/story-staff-role-claim-and-admin-route-protection.md`. Architecture: `_bmad-output/planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md` AD-7 ("Public GETs need no token; every write needs authentication; `/api/v1/admin/**` requires `STAFF`"), AD-8 ("Roles `USER` and `STAFF` ride as a claim and are enforced only server-side … Role changes take effect at the next access-token expiry"), AD-13 ("Staff endpoints live in the module that owns the entity (`/api/v1/admin/<module>/**`)").

**Branch:** MBD-20 must be merged first (this plan edits the `SecurityConfig` it touched). `git switch main && git pull && git switch -c story/mbd-21-staff-role`. Commit messages end with `(MBD-21)` and the `Co-Authored-By` trailer. Run Gradle from `api/` (`./gradlew` in Git Bash, `.\gradlew.bat` in PowerShell). Docker must be running for Testcontainers. `docs/postman/` is untracked local work: never `git add -A`; add files by path.

## Decisions (defaults chosen for this story)

> **2026-10-08, after the final review:** the bootstrap was changed from the `R__` repeatable migration to an `afterMigrate__promote_bootstrap_staff.sql` callback (verified accounts only). The repeatable migration wrote a `flyway_schema_history` row, so any pre-MBD-21 image failed validation and rollbacks broke; a callback records nothing. Mentions of `R__` and `${flyway:timestamp}` below describe the original design.

| Question | Decision |
|---|---|
| Where the role lives | `accounts.accounts.role text NOT NULL DEFAULT 'USER'`, `CHECK (role IN ('USER','STAFF'))`. One role per account; STAFF implies everything a USER can do. |
| Claim | `"role": "USER"` or `"role": "STAFF"` (a single string). Mapped to authority `ROLE_<value>`. |
| When a role change applies | At the next access token (login or refresh reads the column). No deny-list; a demoted Staff keeps admin for ≤ 15 min (AD-8). |
| Admin rule | `/api/v1/admin/**` (also the bare `/api/v1/admin`), **every method**, `hasRole("STAFF")`. It is the first matcher after error dispatches, so the public-GET rule can never open an admin route. No token → 401; USER → 403. |
| Other writes and `/api/v1/accounts/me` | `hasAnyRole("USER", "STAFF")`. A token with no `role` claim (issued before this deploy, ≤ 15 min old) gets 403 on writes and admin until it is refreshed. The SPA is not live yet (MBD-22), so nobody notices. |
| 403 body | Problem Details, `{"type":"about:blank","title":"Forbidden","status":403,"detail":"Not allowed"}`; never says which role was needed. |
| Admin routes in this story | **None shipped.** MBD-28 adds the first real one. Tests use a test-only controller under `/api/v1/admin/probe`. On a running stack the rule is visible as 403 (USER) vs 404 (STAFF, passed security, no route). |
| Bootstrap | `R__promote_bootstrap_staff.sql`, re-run on every start. Empty variable → matches nobody. The account must exist first: register, verify, set the variable, restart the api. Removing the variable does **not** demote (demotion is a manual `UPDATE`, see runbook). |
| Bootstrap email validation | At startup, in Java: stripped, lowercased, must match `[^'\s@]+@[^'\s@]+` or the api refuses to start (the value is pasted into SQL by Flyway). |
| Role in `GET /accounts/me` | Not added. The SPA's `/admin` area (MBD-28) decides whether it needs it. |
| Self-service promotion / admin user management | Out of scope (MVP). |

## Global Constraints

- Roles are enforced only server-side (AD-8). The client never sends a role; the only source is the `accounts.accounts.role` column, read at token issue.
- `/api/v1/admin/**` requires `STAFF` for every HTTP method (AD-7). The matcher stays the first authorization rule after the error-dispatch rule.
- Public GETs need no token; every write needs a USER or STAFF token (AD-7). The public auth endpoints (register, login, verify, verification-email, refresh POST/DELETE) stay `permitAll`.
- 401 and 403 responses from the security chain are `application/problem+json` and reveal nothing about the token or the required role.
- Migrations: `accounts` schema only, `api/src/main/resources/db/migration/accounts/`. Next version is `V4`. Never edit an applied `V` file.
- Secrets/config from env (AD-11): the bootstrap email is `MUSICBOXD_BOOTSTRAP_STAFF_EMAIL` in `/etc/musicboxd/api.env`; it is optional (empty = no bootstrap).
- `security` must not import from `accounts` (accounts already depends on `security.JwtConfig`): the claim name constant lives in `JwtConfig`.
- Java sources use tabs. Repositories stay package-private. Routes are documented by springdoc.
- Out of scope: real admin endpoints (MBD-28), SPA admin area, staff management UI/API, per-resource ownership checks (each write story owns its own).

## Review Focus

- **A public-GET rule opening an admin route.** `GET /api/v1/admin/...` with no token must be 401, not 200 — the existing `GET /api/** permitAll` would allow it if the admin matcher were placed below it. (Task 2: `adminRoutesRejectAnonymousCallers` covers GET.)
- **Every method and many paths, not one** (the story's high-risk note). A USER must get 403 for GET/POST/PUT/PATCH/DELETE on routes that exist, routes that don't, the bare `/api/v1/admin` and `/api/v1/admin/`. (Task 2: `userTokenIsForbiddenOnEveryAdminRoute`, parameterized.)
- **A token issued before the deploy (no `role` claim), or with an unknown role value.** It must not reach admin and must not crash. (Task 2: `tokensWithoutAKnownRoleAreForbiddenOnAdmin`.)
- **Path tricks around the prefix** (`/api/v1//admin/x`, `/api/v1/./admin/x`, `/api/v1/admin;x=1/y`, trailing slash). Must never be 200 for a USER. (Task 2: `pathTricksNeverReachAnAdminRouteAsUser`.)
- **The bootstrap not taking effect because the account was registered after the first deploy, or a malformed variable.** Promotion must re-run on the next start; a quote in the variable must stop startup, not break SQL. (Task 3: `promotionRerunsOnEveryMigrate`, `bootstrapEmailIsValidated`.)

---

## File Structure

**Role on the account and in the token (Task 1)**
- Create `api/src/main/resources/db/migration/accounts/V4__account_role.sql`: the column.
- Create `api/src/main/java/com/musicboxd/api/accounts/Role.java`: `enum Role { USER, STAFF }`.
- Modify `api/src/main/java/com/musicboxd/api/accounts/Account.java`: `role` component.
- Modify `api/src/main/java/com/musicboxd/api/accounts/AccountRepository.java`: select `role`.
- Modify `api/src/main/java/com/musicboxd/api/accounts/AccountService.java`: `roleOf(UUID)`; register builds a USER.
- Modify `api/src/main/java/com/musicboxd/api/security/JwtConfig.java`: `ROLE_CLAIM`.
- Modify `api/src/main/java/com/musicboxd/api/accounts/TokenService.java`: `issue(UUID, Role)`.
- Modify `api/src/main/java/com/musicboxd/api/accounts/AuthController.java`: pass the current role.
- Tests: `TokenServiceTest`, `AccountServiceTest`, `RefreshFlowTest`; callers updated in `AuthFlowTest`, `SecurityRulesTest`.

**Enforcement (Task 2)**
- Modify `api/src/main/java/com/musicboxd/api/security/SecurityConfig.java`: role converter, admin rule, `hasAnyRole` for writes, 403 handler.
- Create `api/src/main/java/com/musicboxd/api/security/ProblemDetailsAccessDeniedHandler.java`.
- Create `api/src/test/java/com/musicboxd/api/security/AdminRouteProtectionTest.java` (with a nested test-only admin controller).

**Bootstrap (Task 3)**
- Modify `api/src/main/java/com/musicboxd/api/db/ModuleFlyway.java`: placeholders overload.
- Modify `api/src/main/java/com/musicboxd/api/accounts/AccountsConfig.java`: pass the validated email.
- Create `api/src/main/resources/db/migration/accounts/R__promote_bootstrap_staff.sql`.
- Modify `api/src/main/resources/application.yml`, `deploy/docker-compose.yml`, `deploy/prod.env.example`.
- Create `docs/runbooks/runbook-mbd-21-staff-bootstrap.md`.
- Create `api/src/test/java/com/musicboxd/api/accounts/StaffBootstrapTest.java`.

**Ship check (Task 4)**: no code; full suite plus the manual multi-route 403 check.

---

### Task 1: Role column, `role` claim, read at every token issue

**Files:**
- Create: `api/src/main/resources/db/migration/accounts/V4__account_role.sql`
- Create: `api/src/main/java/com/musicboxd/api/accounts/Role.java`
- Modify: `api/src/main/java/com/musicboxd/api/accounts/Account.java`
- Modify: `api/src/main/java/com/musicboxd/api/accounts/AccountRepository.java`
- Modify: `api/src/main/java/com/musicboxd/api/accounts/AccountService.java`
- Modify: `api/src/main/java/com/musicboxd/api/security/JwtConfig.java`
- Modify: `api/src/main/java/com/musicboxd/api/accounts/TokenService.java`
- Modify: `api/src/main/java/com/musicboxd/api/accounts/AuthController.java:144-150` (`withSession`)
- Test: `api/src/test/java/com/musicboxd/api/accounts/TokenServiceTest.java`
- Test: `api/src/test/java/com/musicboxd/api/accounts/AccountServiceTest.java`
- Test: `api/src/test/java/com/musicboxd/api/accounts/RefreshFlowTest.java`
- Modify (callers): `api/src/test/java/com/musicboxd/api/accounts/AuthFlowTest.java:137-139`, `api/src/test/java/com/musicboxd/api/security/SecurityRulesTest.java:47`

**Interfaces:**
- Consumes: `AccountRepository.findById(UUID) -> Optional<Account>`, `AccountNotFoundException`, `JwtConfig.ISSUER`.
- Produces:
  - `public enum Role { USER, STAFF }` in `com.musicboxd.api.accounts`.
  - `record Account(UUID id, String email, String passwordHash, OffsetDateTime emailVerifiedAt, Role role)`.
  - `public Role AccountService.roleOf(UUID accountId)` — throws `AccountNotFoundException` for an unknown id.
  - `public static final String JwtConfig.ROLE_CLAIM = "role"`.
  - `public AccessToken TokenService.issue(UUID accountId, Role role)` (replaces `issue(UUID)`).

- [ ] **Step 1: Write the failing tests**

In `TokenServiceTest.java`, change every `.issue(accountId)` / `.issue(UUID.randomUUID())` call to pass `Role.USER` as the second argument (three call sites), and add:

```java
	@Test
	void tokenCarriesTheRoleClaim() {
		var staff = serviceAt(Instant.now(), props).issue(UUID.randomUUID(), Role.STAFF);
		var user = serviceAt(Instant.now(), props).issue(UUID.randomUUID(), Role.USER);

		assertThat(decoder.decode(staff.value()).getClaimAsString(JwtConfig.ROLE_CLAIM)).isEqualTo("STAFF");
		assertThat(decoder.decode(user.value()).getClaimAsString(JwtConfig.ROLE_CLAIM)).isEqualTo("USER");
	}
```

In `AccountServiceTest.java`, add (it needs `import org.springframework.dao.DataIntegrityViolationException;`):

```java
	@Test
	void newAccountsAreUsersAndTheColumnRejectsUnknownRoles() {
		AccountView view = accounts.register(uniqueEmail(), PASSWORD, uniqueUsername());

		assertThat(accounts.roleOf(view.id())).isEqualTo(Role.USER);
		assertThatThrownBy(() -> jdbc.sql("UPDATE accounts.accounts SET role = 'ROOT' WHERE id = :id")
			.param("id", view.id()).update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void roleOfAnUnknownAccountFails() {
		assertThatThrownBy(() -> accounts.roleOf(UUID.randomUUID())).isInstanceOf(AccountNotFoundException.class);
	}
```

In `RefreshFlowTest.java`:

1. Add imports `import org.springframework.security.oauth2.jwt.JwtDecoder;` and `import com.musicboxd.api.security.JwtConfig;`.
2. Add a field next to the others:

```java
	@Autowired
	private JwtDecoder jwtDecoder;
```

3. Add this test after `aFreshLoginStillWorksAfterLogout`:

```java
	@Test
	void accessTokensCarryTheRoleAndARoleChangeTakesEffectAtTheNextRefresh() throws Exception {
		String email = verifiedAccount();
		MvcResult first = login(email).andExpect(status().isOk()).andReturn();
		assertThat(roleClaim(first)).isEqualTo("USER");

		// AD-8: no API promotes anyone; Staff is assigned in the database (MBD-21 bootstrap or by hand).
		jdbc.sql("UPDATE accounts.accounts SET role = 'STAFF' WHERE lower(email) = lower(:email)")
			.param("email", email).update();

		MvcResult refreshed = refresh(refreshCookie(first)).andExpect(status().isOk()).andReturn();
		assertThat(roleClaim(refreshed)).isEqualTo("STAFF");
	}
```

4. Add this helper next to `refreshCookie(MvcResult)`:

```java
	private String roleClaim(MvcResult result) throws Exception {
		String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
		return jwtDecoder.decode(token).getClaimAsString(JwtConfig.ROLE_CLAIM);
	}
```

Update the two other callers so the test sources compile against the new signature:

- `AuthFlowTest.java` (around line 139): `.issue(UUID.randomUUID());` → `.issue(UUID.randomUUID(), Role.USER);`
- `SecurityRulesTest.java` line 47: `tokens.issue(UUID.randomUUID()).value()` → `tokens.issue(UUID.randomUUID(), Role.USER).value()` and add `import com.musicboxd.api.accounts.Role;`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.musicboxd.api.accounts.*"`
Expected: compilation FAILS with `cannot find symbol ... class Role` and `method issue ... cannot be applied`.

- [ ] **Step 3: Add the migration**

Create `api/src/main/resources/db/migration/accounts/V4__account_role.sql`:

```sql
-- MBD-21: one role per account (AD-8). STAFF manages the catalog through /api/v1/admin/**.
-- It rides in the access token as the `role` claim, read here at every login and refresh,
-- so a change takes effect at the next access-token expiry. Every existing account is a USER.
ALTER TABLE accounts.accounts
	ADD COLUMN role text NOT NULL DEFAULT 'USER'
	CONSTRAINT accounts_role_check CHECK (role IN ('USER', 'STAFF'));
```

- [ ] **Step 4: Add `Role` and carry it on `Account`**

Create `api/src/main/java/com/musicboxd/api/accounts/Role.java`:

```java
package com.musicboxd.api.accounts;

/** AD-8: rides in the access token as the {@code role} claim and is enforced only server-side. */
public enum Role {
	USER, STAFF
}
```

Replace `Account.java` with:

```java
package com.musicboxd.api.accounts;

import java.time.OffsetDateTime;
import java.util.UUID;

record Account(UUID id, String email, String passwordHash, OffsetDateTime emailVerifiedAt, Role role) {

	boolean emailVerified() {
		return emailVerifiedAt != null;
	}

	@Override
	public String toString() {
		return "Account[id=" + id + ", email=" + email + ", passwordHash=***, emailVerified=" + emailVerified()
				+ ", role=" + role + "]";
	}
}
```

In `AccountRepository.java`, change both `SELECT id, email, password_hash, email_verified_at FROM` to `SELECT id, email, password_hash, email_verified_at, role FROM` (in `findByEmail` and `findById`). `insert` is unchanged: the column default makes new rows USER. (`JdbcClient.query(Account.class)` converts the `text` column to the enum.)

In `AccountService.java`:

1. In `register`, change the constructor call to:

```java
		var account = new Account(UUID.randomUUID(), normalize(email), passwordEncoder.encode(password), null,
				Role.USER);
```

2. Add after `describe(...)`:

```java
	/** Read at every token issue (login and refresh), so a role change applies at the next access token (AD-8). */
	@Transactional(readOnly = true)
	public Role roleOf(UUID accountId) {
		return accounts.findById(accountId).map(Account::role).orElseThrow(AccountNotFoundException::new);
	}
```

- [ ] **Step 5: Put the role in the token**

In `JwtConfig.java`, add under `ISSUER`:

```java
	/** Access-token claim holding the account's role, USER or STAFF (MBD-21). */
	public static final String ROLE_CLAIM = "role";
```

In `TokenService.java`, change the class Javadoc to `/** Issues short-lived access tokens carrying the account's role (AD-8). */` and replace `issue`:

```java
	public AccessToken issue(UUID accountId, Role role) {
		Instant now = clock.instant();
		var claims = JwtClaimsSet.builder()
			.issuer(JwtConfig.ISSUER)
			.subject(accountId.toString())
			.claim(JwtConfig.ROLE_CLAIM, role.name())
			.issuedAt(now)
			.expiresAt(now.plus(props.accessTokenTtl()))
			.build();
		var header = JwsHeader.with(MacAlgorithm.HS256).build();
		String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new AccessToken(value, props.accessTokenTtl().toSeconds());
	}
```

In `AuthController.java`, in `withSession`, replace `var access = tokens.issue(accountId);` with:

```java
		// The current role, from the database: a promotion or demotion shows up at the next login or refresh.
		var access = tokens.issue(accountId, accounts.roleOf(accountId));
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.musicboxd.api.accounts.*" --tests "com.musicboxd.api.security.SecurityRulesTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add api/src/main/resources/db/migration/accounts/V4__account_role.sql \
        api/src/main/java/com/musicboxd/api/accounts/Role.java \
        api/src/main/java/com/musicboxd/api/accounts/Account.java \
        api/src/main/java/com/musicboxd/api/accounts/AccountRepository.java \
        api/src/main/java/com/musicboxd/api/accounts/AccountService.java \
        api/src/main/java/com/musicboxd/api/security/JwtConfig.java \
        api/src/main/java/com/musicboxd/api/accounts/TokenService.java \
        api/src/main/java/com/musicboxd/api/accounts/AuthController.java \
        api/src/test/java/com/musicboxd/api/accounts/TokenServiceTest.java \
        api/src/test/java/com/musicboxd/api/accounts/AccountServiceTest.java \
        api/src/test/java/com/musicboxd/api/accounts/RefreshFlowTest.java \
        api/src/test/java/com/musicboxd/api/accounts/AuthFlowTest.java \
        api/src/test/java/com/musicboxd/api/security/SecurityRulesTest.java
git commit -m "Add the account role and carry it as the access token's role claim (MBD-21)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Enforce STAFF on `/api/v1/admin/**` and a role on every write

**Files:**
- Modify: `api/src/main/java/com/musicboxd/api/security/SecurityConfig.java`
- Create: `api/src/main/java/com/musicboxd/api/security/ProblemDetailsAccessDeniedHandler.java`
- Test: `api/src/test/java/com/musicboxd/api/security/AdminRouteProtectionTest.java`

**Interfaces:**
- Consumes: `TokenService.issue(UUID, Role) -> AccessToken` and `Role` (Task 1); `JwtConfig.ROLE_CLAIM`, `JwtConfig.ISSUER`; `JwtEncoder` bean.
- Produces: the authorization rules in the Decisions table. Authorities `ROLE_USER` / `ROLE_STAFF` on every authenticated request, for later stories that use `@PreAuthorize` or `hasRole`.

- [ ] **Step 1: Write the failing tests**

Create `api/src/test/java/com/musicboxd/api/security/AdminRouteProtectionTest.java`:

```java
package com.musicboxd.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

/** MBD-21 acceptance: /api/v1/admin/** is Staff-only for every method; other writes need USER or STAFF. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, AdminRouteProtectionTest.AdminProbe.class })
class AdminRouteProtectionTest {

	/**
	 * No admin route exists until MBD-28, so this stands in for one. Nested in a test class, component
	 * scanning skips it; only this test's context imports it, so the published contract never lists it.
	 */
	@RestController
	@RequestMapping({ "/api/v1/admin/probe", "/api/v1/admin/probe/**" })
	static class AdminProbe {

		@RequestMapping
		Map<String, Boolean> any() {
			return Map.of("ok", true);
		}
	}

	private static final HttpMethod[] METHODS = { HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
			HttpMethod.PATCH, HttpMethod.DELETE };

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JwtEncoder jwtEncoder;

	/** Every method on routes that exist and routes that don't: a sample, not just one (story's risk note). */
	static Stream<Arguments> adminRequests() {
		var paths = new String[] { "/api/v1/admin/probe", "/api/v1/admin/probe/albums/42",
				"/api/v1/admin/catalog/albums", "/api/v1/admin/reviews/7", "/api/v1/admin", "/api/v1/admin/" };
		return Stream.of(paths).flatMap(path -> Stream.of(METHODS).map(method -> Arguments.of(method, path)));
	}

	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("adminRequests")
	void userTokenIsForbiddenOnEveryAdminRoute(HttpMethod method, String path) throws Exception {
		mockMvc.perform(request(method, path).header("Authorization", bearer(Role.USER)))
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(403))
			.andExpect(jsonPath("$.title").value("Forbidden"));
	}

	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("adminRequests")
	void adminRoutesRejectAnonymousCallers(HttpMethod method, String path) throws Exception {
		mockMvc.perform(request(method, path)).andExpect(status().isUnauthorized());
	}

	@Test
	void staffTokenReachesAdminRoutesWithEveryMethod() throws Exception {
		for (HttpMethod method : METHODS) {
			mockMvc.perform(request(method, "/api/v1/admin/probe/albums/42").header("Authorization", bearer(Role.STAFF)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(true));
		}
		// Past security, an unknown admin route is an honest 404.
		mockMvc.perform(get("/api/v1/admin/catalog/albums").header("Authorization", bearer(Role.STAFF)))
			.andExpect(status().isNotFound());
	}

	@Test
	void tokensWithoutAKnownRoleAreForbiddenOnAdmin() throws Exception {
		// Issued before MBD-21 (no claim), or carrying a value we never issue.
		for (String token : new String[] { tokenWithRole(null), tokenWithRole("ADMIN"), tokenWithRole("staff") }) {
			mockMvc.perform(get("/api/v1/admin/probe").header("Authorization", "Bearer " + token))
				.andExpect(status().isForbidden());
			mockMvc.perform(post("/api/v1/health").header("Authorization", "Bearer " + token))
				.andExpect(status().isForbidden());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1//admin/probe", "/api/v1/./admin/probe", "/api/v1/x/../admin/probe",
			"/api/v1/admin;x=1/probe", "/api/v1/admin/probe/", "/api/v1/admin/probe;jsessionid=1" })
	void pathTricksNeverReachAnAdminRouteAsUser(String path) throws Exception {
		int status = mockMvc.perform(get(path).header("Authorization", bearer(Role.USER)))
			.andReturn().getResponse().getStatus();
		// The firewall rejects the odd ones (400); the rest hit the admin rule (403). Never 200, never 404.
		assertThat(status).isIn(400, 403);
	}

	@Test
	void writesOutsideAdminNeedAUserOrStaffToken() throws Exception {
		mockMvc.perform(post("/api/v1/health")).andExpect(status().isUnauthorized());
		// Past security; no POST handler on /health, so the request is answered by MVC, not the security chain.
		for (Role role : Role.values()) {
			int status = mockMvc.perform(post("/api/v1/health").header("Authorization", bearer(role)))
				.andReturn().getResponse().getStatus();
			assertThat(status).isNotIn(401, 403);
		}
	}

	@Test
	void publicReadsStillNeedNoToken() throws Exception {
		mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/api-docs")).andExpect(status().isOk());
	}

	private String bearer(Role role) {
		return "Bearer " + tokens.issue(UUID.randomUUID(), role).value();
	}

	private String tokenWithRole(String role) {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		var claims = JwtClaimsSet.builder()
			.issuer(JwtConfig.ISSUER)
			.subject(UUID.randomUUID().toString())
			.issuedAt(now)
			.expiresAt(now.plusSeconds(600));
		if (role != null) {
			claims.claim(JwtConfig.ROLE_CLAIM, role);
		}
		var header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}
}
```

Note on `tokenWithRole("staff")`: authorities are case-sensitive (`ROLE_staff` ≠ `ROLE_STAFF`). That is intended; we only ever issue upper case.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.musicboxd.api.security.AdminRouteProtectionTest"`
Expected: FAIL. `userTokenIsForbiddenOnEveryAdminRoute` gets `404`/`200` instead of `403` (no admin rule yet); `adminRoutesRejectAnonymousCallers` gets `200`/`404` on the GET cases (the public-GET rule lets them through); `tokensWithoutAKnownRoleAreForbiddenOnAdmin` gets `200`.

- [ ] **Step 3: Add the 403 handler**

Create `api/src/main/java/com/musicboxd/api/security/ProblemDetailsAccessDeniedHandler.java`:

```java
package com.musicboxd.api.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 403s raised by the security filter chain (a valid token without the role a route needs, MBD-21) as
 * RFC 9457 Problem Details. The bearer handler still sets {@code WWW-Authenticate}; the body never names the role.
 */
final class ProblemDetailsAccessDeniedHandler implements AccessDeniedHandler {

	private static final String BODY = "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
			+ "\"detail\":\"Not allowed\"}";

	private final BearerTokenAccessDeniedHandler delegate = new BearerTokenAccessDeniedHandler();

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException, ServletException {
		delegate.handle(request, response, accessDeniedException);
		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(BODY);
	}
}
```

- [ ] **Step 4: Enforce the roles in `SecurityConfig`**

1. Replace the class Javadoc with:

```java
/**
 * Route-level gating (AD-7, AD-8): public GETs need no token, every write needs a USER or STAFF token,
 * and /api/v1/admin/** needs STAFF for every method (MBD-21). The role comes from the access token's
 * {@code role} claim, which only the API issues.
 */
```

2. Add imports:

```java
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.access.AccessDeniedHandler;
```

3. Replace the body of `apiSecurity` from `AuthenticationEntryPoint entryPoint = ...` through `return http.build();` with:

```java
		AuthenticationEntryPoint entryPoint = new ProblemDetailsAuthenticationEntryPoint();
		AccessDeniedHandler accessDenied = new ProblemDetailsAccessDeniedHandler();
		http
			// Bearer tokens are not cookie-borne, so CSRF does not apply to them. The one cookie, MBD-19's
			// refresh token, is SameSite=Lax (no cross-site POST/DELETE) and scoped to /api/v1/auth/refresh,
			// where POST refreshes and DELETE logs out (AD-8). A forged logout would only log the person out.
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				// Error dispatches carry the original status; don't turn them into 401s.
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				// GUARD: first rule, every method. Below it, the public-GET rule would open admin reads.
				// "/**" also matches the bare /api/v1/admin.
				.requestMatchers("/api/v1/admin/**").hasRole("STAFF")
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
						"/api/v1/auth/verification-email", "/api/v1/auth/refresh").permitAll()
				// Logout: the refresh cookie is the credential; an expired access token must not block it.
				.requestMatchers(HttpMethod.DELETE, "/api/v1/auth/refresh").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/auth/verify").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/accounts/me").hasAnyRole("USER", "STAFF")
				// GUARD: any authenticated GET must be listed ABOVE this line, or it becomes public.
				.requestMatchers(HttpMethod.GET, "/api/**").permitAll()
				.anyRequest().hasAnyRole("USER", "STAFF"))
			.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
			.oauth2ResourceServer(oauth -> oauth
				.bearerTokenResolver(bearerTokenResolver())
				.jwt(jwt -> jwt.jwtAuthenticationConverter(roleClaimConverter()))
				.authenticationEntryPoint(entryPoint)
				.accessDeniedHandler(accessDenied));
		return http.build();
```

4. Add this method after `bearerTokenResolver()`:

```java
	/**
	 * {@code "role": "STAFF"} becomes the authority {@code ROLE_STAFF}. A token without the claim (issued
	 * before MBD-21) has no role and is refused on writes and admin until it is refreshed.
	 */
	private static JwtAuthenticationConverter roleClaimConverter() {
		var authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName(JwtConfig.ROLE_CLAIM);
		authorities.setAuthorityPrefix("ROLE_");
		var converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}
```

5. `Customizer` is no longer used; remove `import org.springframework.security.config.Customizer;`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.musicboxd.api.security.*"`
Expected: PASS (the new class plus `SecurityRulesTest` and `ErrorDispatchTest`).

If a case in `pathTricksNeverReachAnAdminRouteAsUser` returns `404`, MockMvc normalized the path before the admin matcher saw it but MVC did not: that is a real gap — stop and report it rather than loosening the assertion.

- [ ] **Step 6: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL. In particular `AuthFlowTest` and `RefreshFlowTest` still pass (their tokens now carry `role`, so `/accounts/me` is allowed) and `OpenApiExportBootTest` still boots.

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/security/SecurityConfig.java \
        api/src/main/java/com/musicboxd/api/security/ProblemDetailsAccessDeniedHandler.java \
        api/src/test/java/com/musicboxd/api/security/AdminRouteProtectionTest.java
git commit -m "Require STAFF on every /api/v1/admin route and a role on every write, with 403 Problem Details (MBD-21)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Bootstrap the first STAFF account from an environment variable

**Files:**
- Modify: `api/src/main/java/com/musicboxd/api/db/ModuleFlyway.java`
- Modify: `api/src/main/java/com/musicboxd/api/accounts/AccountsConfig.java`
- Create: `api/src/main/resources/db/migration/accounts/R__promote_bootstrap_staff.sql`
- Modify: `api/src/main/resources/application.yml`
- Modify: `deploy/docker-compose.yml`
- Modify: `deploy/prod.env.example`
- Create: `docs/runbooks/runbook-mbd-21-staff-bootstrap.md`
- Test: `api/src/test/java/com/musicboxd/api/accounts/StaffBootstrapTest.java`

**Interfaces:**
- Consumes: `Role` (Task 1), `AccountService.normalize(String)` (package-private static, exists).
- Produces:
  - `public static Flyway ModuleFlyway.forSchema(DataSource, String schema, Map<String, String> placeholders)` (the 2-argument form delegates with `Map.of()`).
  - `static String AccountsConfig.bootstrapStaffEmail(String raw)` — `""` for null/blank, else the normalized email; throws `IllegalStateException` if it does not match `[^'\s@]+@[^'\s@]+`.
  - `static final String AccountsConfig.STAFF_EMAIL_PLACEHOLDER = "bootstrap_staff_email"`.
  - Property `musicboxd.bootstrap-staff-email` ← env `MUSICBOXD_BOOTSTRAP_STAFF_EMAIL`.

- [ ] **Step 1: Write the failing tests**

Create `api/src/test/java/com/musicboxd/api/accounts/StaffBootstrapTest.java`:

```java
package com.musicboxd.api.accounts;

import static com.musicboxd.api.accounts.AccountServiceTest.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.db.ModuleFlyway;

/** MBD-21: the first STAFF account comes from MUSICBOXD_BOOTSTRAP_STAFF_EMAIL through a repeatable migration. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StaffBootstrapTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void theConfiguredEmailIsPromotedCaseInsensitively() {
		String email = uniqueEmail();
		UUID id = insertAccount(email);
		UUID bystander = insertAccount(uniqueEmail());

		migrateWith(email.toUpperCase());

		assertThat(role(id)).isEqualTo("STAFF");
		assertThat(role(bystander)).isEqualTo("USER");
	}

	@Test
	void promotionRerunsOnEveryMigrate() {
		// First deploy: the variable is set before the person registered, so nobody matches.
		String email = uniqueEmail();
		migrateWith(email);

		// They register; the next start (same variable, nothing else changed) promotes them.
		UUID id = insertAccount(email);
		migrateWith(email);

		assertThat(role(id)).isEqualTo("STAFF");
	}

	@Test
	void anEmptyVariablePromotesNobodyAndNothingDemotes() {
		String email = uniqueEmail();
		UUID id = insertAccount(email);
		migrateWith(email);
		Integer staffBefore = staffCount();

		migrateWith("");

		assertThat(staffCount()).isEqualTo(staffBefore);
		assertThat(role(id)).isEqualTo("STAFF");
	}

	@Test
	void bootstrapEmailIsValidated() {
		assertThat(AccountsConfig.bootstrapStaffEmail(null)).isEmpty();
		assertThat(AccountsConfig.bootstrapStaffEmail("   ")).isEmpty();
		assertThat(AccountsConfig.bootstrapStaffEmail("  Staff@Example.COM ")).isEqualTo("staff@example.com");
		for (String bad : new String[] { "o'brien@example.com", "x' OR '1'='1", "no-at-sign", "a b@example.com",
				"@example.com", "staff@" }) {
			assertThatThrownBy(() -> AccountsConfig.bootstrapStaffEmail(bad))
				.as(bad)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("MUSICBOXD_BOOTSTRAP_STAFF_EMAIL");
		}
	}

	private void migrateWith(String email) {
		ModuleFlyway.forSchema(dataSource, "accounts",
				Map.of(AccountsConfig.STAFF_EMAIL_PLACEHOLDER, AccountsConfig.bootstrapStaffEmail(email)))
			.migrate();
	}

	private UUID insertAccount(String email) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO accounts.accounts (id, email, password_hash) VALUES (:id, :email, '{noop}x')")
			.param("id", id).param("email", email).update();
		return id;
	}

	private String role(UUID id) {
		return jdbc.sql("SELECT role FROM accounts.accounts WHERE id = :id").param("id", id).query(String.class).single();
	}

	private Integer staffCount() {
		return jdbc.sql("SELECT count(*) FROM accounts.accounts WHERE role = 'STAFF'").query(Integer.class).single();
	}
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew test --tests "com.musicboxd.api.accounts.StaffBootstrapTest"`
Expected: compilation FAILS with `cannot find symbol ... STAFF_EMAIL_PLACEHOLDER`, `bootstrapStaffEmail`, and `method forSchema ... cannot be applied`.

- [ ] **Step 3: Let `ModuleFlyway` take placeholders**

Replace the body of `ModuleFlyway` (keep the class Javadoc and private constructor) with:

```java
	public static Flyway forSchema(DataSource dataSource, String schema) {
		return forSchema(dataSource, schema, Map.of());
	}

	/** Placeholders are substituted as plain text into the module's SQL: validate any value that comes from outside. */
	public static Flyway forSchema(DataSource dataSource, String schema, Map<String, String> placeholders) {
		return Flyway.configure()
			.dataSource(dataSource)
			.schemas(schema)
			.defaultSchema(schema)
			.createSchemas(true)
			.locations("classpath:db/migration/" + schema)
			.placeholders(placeholders)
			.load();
	}
```

Add `import java.util.Map;`.

- [ ] **Step 4: Add the repeatable migration**

Create `api/src/main/resources/db/migration/accounts/R__promote_bootstrap_staff.sql`:

```sql
-- MBD-21: the first STAFF account. There is no self-service promotion in the MVP; the email comes from
-- MUSICBOXD_BOOTSTRAP_STAFF_EMAIL (validated and lowercased by AccountsConfig). Empty matches nobody.
-- ${flyway:timestamp} changes the checksum on every start, so Flyway re-applies this file each time:
-- registering the account after the variable was set needs only an api restart. It never demotes.
UPDATE accounts.accounts SET role = 'STAFF' WHERE lower(email) = '${bootstrap_staff_email}';
```

- [ ] **Step 5: Wire the property into the accounts Flyway**

In `AccountsConfig.java`, add imports `java.util.Map` and `org.springframework.beans.factory.annotation.Value`, then replace `accountsFlyway` with:

```java
	static final String STAFF_EMAIL_PLACEHOLDER = "bootstrap_staff_email";

	// Off only for the OpenAPI export boot (web/Dockerfile), which has no database.
	@ConditionalOnProperty(name = "musicboxd.migrations.enabled", havingValue = "true", matchIfMissing = true)
	@Bean(initMethod = "migrate")
	Flyway accountsFlyway(DataSource dataSource, @Value("${musicboxd.bootstrap-staff-email:}") String staffEmail) {
		return ModuleFlyway.forSchema(dataSource, "accounts",
				Map.of(STAFF_EMAIL_PLACEHOLDER, bootstrapStaffEmail(staffEmail)));
	}

	/**
	 * The value is pasted into SQL by Flyway, so anything that is not a plain address stops startup.
	 * Lowercased like every stored email (AccountService.normalize).
	 */
	static String bootstrapStaffEmail(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		String email = AccountService.normalize(raw);
		if (!email.matches("[^'\\s@]+@[^'\\s@]+")) {
			throw new IllegalStateException(
					"musicboxd.bootstrap-staff-email (env MUSICBOXD_BOOTSTRAP_STAFF_EMAIL) must be a plain email address");
		}
		return email;
	}
```

In `application.yml`, under `musicboxd:` (after the `auth:` block, before `verification:`), add:

```yaml
  # MBD-21: promotes this account to STAFF on every start (R__promote_bootstrap_staff.sql). Optional;
  # the account must already exist (runbook-mbd-21-staff-bootstrap.md). Removing it does not demote.
  bootstrap-staff-email: ${MUSICBOXD_BOOTSTRAP_STAFF_EMAIL:}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./gradlew test --tests "com.musicboxd.api.accounts.StaffBootstrapTest"`
Expected: PASS.

If `promotionRerunsOnEveryMigrate` fails (the second `migrate` reports nothing to apply), Flyway did not include `${flyway:timestamp}` in the repeatable checksum. Do not work around it silently: stop and report, since the runbook's "restart to promote" depends on it.

- [ ] **Step 7: Deploy config and runbook**

In `deploy/docker-compose.yml`, under the `api` service's `environment:` (after `MUSICBOXD_PUBLIC_BASE_URL`), add:

```yaml
      # MBD-21 local dev: register this email, verify it, then restart the api to make it STAFF.
      MUSICBOXD_BOOTSTRAP_STAFF_EMAIL: staff@localhost.test
```

In `deploy/prod.env.example`, at the end of the `--- api.env ---` block, add:

```
# MBD-21: the first STAFF account (runbook-mbd-21-staff-bootstrap.md). Optional. Register and verify the
# account first, then set this and restart the api. Removing it later does not demote anyone.
MUSICBOXD_BOOTSTRAP_STAFF_EMAIL=
```

Create `docs/runbooks/runbook-mbd-21-staff-bootstrap.md`:

````markdown
# Runbook MBD-21 — The first STAFF account, and checking admin protection

STAFF is a column on `accounts.accounts` (`USER` by default). There is no API that promotes anyone. The api
promotes the email in `MUSICBOXD_BOOTSTRAP_STAFF_EMAIL` on **every start**, through the repeatable migration
`R__promote_bootstrap_staff.sql`. The role reaches the access token at the next login or refresh (≤ 15 minutes).

## First production deploy (one time, a person does this)

1. After MBD-21 is deployed, register the Staff account through the site (or `POST /api/v1/auth/register`) and
   click the verification link.
2. On the EC2 host (SSM session), add the variable and restart the api:

   ```bash
   sudo sh -c 'printf "MUSICBOXD_BOOTSTRAP_STAFF_EMAIL=%s\n" "staff@your-domain" >> /etc/musicboxd/api.env'
   sudo docker compose -f /opt/musicboxd/docker-compose.prod.yml --env-file /etc/musicboxd/stack.env up -d api
   ```

   A malformed value (a quote, a space, no `@`) stops the api at startup with a message naming the variable;
   `deploy.sh` then rolls back. Fix the value and restart.
3. Confirm:

   ```bash
   sudo docker compose -f /opt/musicboxd/docker-compose.prod.yml --env-file /etc/musicboxd/stack.env exec postgres \
     psql -U musicboxd -d musicboxd -c "SELECT email, role FROM accounts.accounts WHERE role = 'STAFF';"
   ```

4. Log out and in again (or wait for the next refresh): the new access token carries `"role":"STAFF"`.

## Demoting someone

Removing the variable does not demote. Do it by hand; it applies at their next access token (≤ 15 min):

```sql
UPDATE accounts.accounts SET role = 'USER' WHERE lower(email) = lower('someone@example.com');
```

Also remove or change `MUSICBOXD_BOOTSTRAP_STAFF_EMAIL`, or the next restart promotes them again.

## Checking admin protection (before merge and after each deploy)

The story's risk note asks for a **sample** of admin routes, not one. With `USER` and `STAFF` access tokens
(`$U`, `$S`) from `POST /api/v1/auth/login`:

```bash
for m in GET POST PUT PATCH DELETE; do
  for p in /api/v1/admin /api/v1/admin/catalog/albums /api/v1/admin/catalog/albums/1 /api/v1/admin/reviews/1; do
    printf '%-6s %-34s user=%s staff=%s anon=%s\n' $m $p \
      "$(curl -s -o /dev/null -w '%{http_code}' -X $m -H "Authorization: Bearer $U" $D$p)" \
      "$(curl -s -o /dev/null -w '%{http_code}' -X $m -H "Authorization: Bearer $S" $D$p)" \
      "$(curl -s -o /dev/null -w '%{http_code}' -X $m $D$p)"
  done
done
```

Expected: every `user=403`, every `anon=401`, and `staff` never 401/403 (404 until MBD-28 adds real admin routes).
````

- [ ] **Step 8: Run the full suite**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

Also run the compose check from the repo root (Git Bash): `bash deploy/tests/test-compose-config.sh`
Expected: exits 0 (the new env line is optional and unset there).

- [ ] **Step 9: Commit**

```bash
git add api/src/main/java/com/musicboxd/api/db/ModuleFlyway.java \
        api/src/main/java/com/musicboxd/api/accounts/AccountsConfig.java \
        api/src/main/resources/db/migration/accounts/R__promote_bootstrap_staff.sql \
        api/src/main/resources/application.yml \
        api/src/test/java/com/musicboxd/api/accounts/StaffBootstrapTest.java \
        deploy/docker-compose.yml deploy/prod.env.example \
        docs/runbooks/runbook-mbd-21-staff-bootstrap.md
git commit -m "Promote the first STAFF account from MUSICBOXD_BOOTSTRAP_STAFF_EMAIL on every start (MBD-21)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Ship check (the story's high-risk note, hitl)

No code. A person confirms 403 on a **sample** of admin routes on a running stack, and that the bootstrap works.

- [ ] **Step 1: Start the dev stack**

Run from the repo root: `docker compose -f deploy/docker-compose.yml up --build -d`

- [ ] **Step 2: Register and verify a USER and the bootstrap STAFF email (Git Bash)**

```bash
D=http://localhost
reg() { curl -s -H 'content-type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"correct-horse\",\"username\":\"$2\"}" $D/api/v1/auth/register; echo; }
USER_EMAIL="mbd21-$RANDOM@example.com"
reg "$USER_EMAIL" "mbd21_$RANDOM"
reg staff@localhost.test "staff_$RANDOM"
# Dev logs the emails instead of sending them. Open both links:
docker compose -f deploy/docker-compose.yml logs api | grep -o "http[^ ]*verify?token=[^ ]*" | tail -2
curl -s "<link 1>"; curl -s "<link 2>"
```

- [ ] **Step 3: Restart the api so the bootstrap promotes the now-existing account**

```bash
docker compose -f deploy/docker-compose.yml restart api
docker compose -f deploy/docker-compose.yml exec postgres psql -U musicboxd -d musicboxd \
  -c "SELECT email, role FROM accounts.accounts WHERE role = 'STAFF';"
```

Expected: one row, `staff@localhost.test | STAFF`.

- [ ] **Step 4: Log both in and inspect the claim**

```bash
tok() { curl -s -H 'content-type: application/json' -d "{\"email\":\"$1\",\"password\":\"correct-horse\"}" \
  $D/api/v1/auth/login | sed -E 's/.*"accessToken":"([^"]+)".*/\1/'; }
U=$(tok "$USER_EMAIL"); S=$(tok staff@localhost.test)
for t in $U $S; do echo "$t" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null; echo; done
```

Expected: the payloads show `"role":"USER"` and `"role":"STAFF"`.

- [ ] **Step 5: The sample of admin routes**

Run the loop from `docs/runbooks/runbook-mbd-21-staff-bootstrap.md` ("Checking admin protection") with `D`, `U`, `S` set above.
Expected: all 20 rows `user=403 staff=404 anon=401`. Also `curl -s -i -H "Authorization: Bearer $U" $D/api/v1/admin/catalog/albums | head -12` shows `Content-Type: application/problem+json` and `"title":"Forbidden"`.

- [ ] **Step 6: Public reads and writes**

```bash
curl -s -o /dev/null -w '%{http_code}\n' $D/api/v1/health                                   # 200, no token
curl -s -o /dev/null -w '%{http_code}\n' -X POST $D/api/v1/health                           # 401, no token
curl -s -o /dev/null -w '%{http_code}\n' $D/api/v1/accounts/me -H "Authorization: Bearer $U" # 200
```

- [ ] **Step 7: Stop the stack, push, open the PR**

```bash
docker compose -f deploy/docker-compose.yml down
git push -u origin story/mbd-21-staff-role
```

Open the PR against `main`. In the description, include the Decisions table above, the output of Step 5, and a **hitl** reminder: on the first production deploy a person registers the Staff account, sets `MUSICBOXD_BOOTSTRAP_STAFF_EMAIL` in `/etc/musicboxd/api.env` and restarts the api (runbook-mbd-21-staff-bootstrap.md).
