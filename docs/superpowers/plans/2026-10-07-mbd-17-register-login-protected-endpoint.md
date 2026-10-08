# MBD-17 Tracer Bullet: Register, Login, and a Protected Endpoint — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A new person registers with email, password, and username, logs in to get a ~15-minute JWT access token, and calls a protected endpoint with it; a wrong password is rejected; the username lives in the `profiles` schema, not `accounts`.

**Architecture:** The API gets its first database access: Spring JDBC (`JdbcClient`) on Postgres 18, with one Flyway instance per module schema (`accounts`, `profiles`) per the spine's Migrations convention. A minimal `profiles` module (table `profiles.profiles(id, username)`) exposes a public facade `ProfilesApi`; `accounts` (table `accounts.accounts`) calls it inside the registration transaction, so the account and profile are created atomically (AD-1, AD-13). Passwords are hashed with BCrypt through Spring Security's `DelegatingPasswordEncoder`. Login issues an HS256 JWT (subject = account id, 15 min) via Spring Security's `NimbusJwtEncoder`; the same key validates bearer tokens through Spring Security's OAuth2 resource server. `GET /api/v1/accounts/me` is the protected endpoint: it returns the caller's id, email, and username (the username read through `ProfilesApi`).

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring Framework 7, Spring Security 7), Spring JDBC, Flyway (`spring-boot-starter-flyway` + `flyway-database-postgresql`), PostgreSQL 18, springdoc-openapi 3.1.1, JUnit 5 / MockMvc / AssertJ, Testcontainers (Postgres), Docker Compose v2, bash deploy tests.

**Spec:** Jira MBD-17 / `_bmad-output/initiative-musicboxd/epic-contas-acesso/story-tracer-bullet-register-login-and-a-protected-endpoint.md`; parent epic `_bmad-output/initiative-musicboxd/epic-contas-acesso/epic-contas-acesso.md`; `_bmad-output/planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md` (AD-1, AD-7, AD-8, AD-11, AD-13, Consistency Conventions).

**Branch:** work in the main checkout (no worktree). Before Task 1, create `story/mbd-17-register-login` from an up-to-date `main` (`git switch main && git pull && git switch -c story/mbd-17-register-login`). Commit messages end with `(MBD-17)` and the `Co-Authored-By` trailer.

## Global Constraints

- Password minimum 8 characters; username 3-20 characters, `[A-Za-z0-9_]` only (user decision, 2026-09-27).
- Username is owned by `profiles` (AD-13): it is stored only in `profiles.profiles`; `accounts.accounts` has no username column. This ticket creates the `profiles` stub with exactly `(id, username)` plus `created_at`; epic-perfil-favoritos extends the same table later and never recreates it.
- The account and its profile are created in one transaction (registration either creates both or neither).
- Passwords hashed with BCrypt or Argon2 (AD-8) — this plan uses BCrypt via `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (stored as `{bcrypt}...`).
- Access token is a JWT of about 15 minutes (AD-8): `musicboxd.auth.access-token-ttl: 15m`.
- All routes under `/api/v1`, described by springdoc OpenAPI (AD-7). Public `GET`s need no token; every write needs authentication, except `POST /api/v1/auth/register` and `POST /api/v1/auth/login`.
- Errors are RFC 9457 Problem Details (Consistency Conventions).
- Schema changes only via versioned Flyway migrations, one Flyway instance and migration set per module schema (`classpath:db/migration/<schema>`); Boot 4 needs `spring-boot-starter-flyway` plus `flyway-database-postgresql`.
- No cross-schema foreign keys: `profiles.profiles.id` equals the account id by convention only (AD-1/AD-12 style references by id).
- A module reads another module only through its public facade (AD-1): `accounts` uses `ProfilesApi` only; profiles internals (`ProfileRepository`, `ProfilesConfig`) are package-private so the compiler enforces this.
- Secrets come from environment files on the host, never the repository (AD-11): the JWT signing key is `MUSICBOXD_JWT_SECRET` in `/etc/musicboxd/api.env`; the app refuses to start without it.
- Java sources use tabs; packages are `com.musicboxd.api.<module>` (the existing code's convention; the spine's `com.musicboxd.<module>` is not used anywhere yet — keep consistency with the code).
- Out of scope (later tickets): email verification (MBD-18), refresh tokens/logout (MBD-19), `USER`/`STAFF` role claim and `/api/v1/admin/**` (MBD-21), SPA screens (MBD-22), login/registration rate limits (MBD-23), per-module Postgres roles and automated module-boundary verification (AD-1; not ticketed — flag to the user, see "Known gaps" at the end).

## Review Focus

- Passwords longer than 72 UTF-8 bytes (BCrypt's limit; Spring Security throws on longer input): registration must answer 400 and login must answer 401 — never a 500. A 37-character password of `é` is 74 bytes and must be caught even though it is under 72 characters. (Task 2 service tests, Task 4 HTTP test.)
- Email case and whitespace: `"  Alice@Example.com "` registers as `alice@example.com`, logs in with any casing, and a second registration differing only in case gets 409. (Task 2.)
- A username taken by a case-only variant (`Alice_1` vs `alice_1`) is a 409 and leaves no orphan account behind — the same email can then register with a different username. (Task 1 uniqueness, Task 2 rollback test.)
- Unknown email and wrong password must be indistinguishable: same status (401) and identical Problem Details body. (Task 2 service, Task 4 HTTP test comparing bodies.)
- Missing, malformed, expired, or foreign-key-signed bearer tokens on the protected endpoint all produce 401, not 500, and errors on permitted routes are not masked as 401 by the error dispatch. (Task 3 decoder tests, Task 4 HTTP tests.)

---

## File Structure

**Persistence foundation (Task 1)**
- Modify `api/build.gradle.kts`: JDBC, Postgres driver, Flyway, Testcontainers.
- Modify `api/src/main/resources/application.yml`: datasource from `POSTGRES_*` env vars, Boot's single Flyway disabled.
- Create `api/src/main/java/com/musicboxd/api/db/ModuleFlyway.java`: builds a Flyway instance for one module schema.
- Create `api/src/main/java/com/musicboxd/api/db/Constraints.java`: tells which Postgres constraint a `DataAccessException` violated.
- Create `api/src/test/java/com/musicboxd/api/TestcontainersConfiguration.java`: Postgres 18 container for every `@SpringBootTest`.
- Modify `api/src/test/java/com/musicboxd/api/demo/DemoOpenApiTest.java`, `DemoRateLimitControllerTest.java`: import the container config.

**profiles stub (Task 1)**
- Create `api/src/main/resources/db/migration/profiles/V1__create_profiles.sql`.
- Create `api/src/main/java/com/musicboxd/api/profiles/ProfilesConfig.java` (Flyway bean), `ProfileRepository.java` (package-private SQL), `ProfilesApi.java` (public facade), `UsernameTakenException.java` (public, 409).
- Create `api/src/test/java/com/musicboxd/api/profiles/ProfilesApiTest.java`.

**accounts domain (Task 2)**
- Create `api/src/main/resources/db/migration/accounts/V1__create_accounts.sql`.
- Create in `api/src/main/java/com/musicboxd/api/accounts/`: `AccountsConfig.java` (Flyway bean, `PasswordEncoder`), `Account.java`, `AccountRepository.java`, `AccountView.java`, `AccountService.java`, `EmailTakenException.java`, `PasswordTooLongException.java`, `InvalidCredentialsException.java`, `AccountNotFoundException.java`.
- Create `api/src/test/java/com/musicboxd/api/accounts/AccountServiceTest.java`.

**Tokens (Task 3)**
- Create `api/src/main/java/com/musicboxd/api/security/AuthProperties.java`, `JwtConfig.java`.
- Create `api/src/main/java/com/musicboxd/api/accounts/TokenService.java`.
- Modify `api/src/main/resources/application.yml`: `musicboxd.auth.*`.
- Create `api/src/test/resources/config/application.yml`: test-only JWT secret.
- Create `api/src/test/java/com/musicboxd/api/accounts/TokenServiceTest.java`, `api/src/test/java/com/musicboxd/api/security/AuthPropertiesTest.java`.

**HTTP + security (Task 4)**
- Create `api/src/main/java/com/musicboxd/api/security/SecurityConfig.java`, `OpenApiConfig.java`.
- Create `api/src/main/java/com/musicboxd/api/accounts/AuthController.java`, `AccountController.java`, `AccountsExceptionHandler.java`.
- Modify `api/src/test/java/com/musicboxd/api/health/HealthControllerTest.java`: import the real security config.
- Create `api/src/test/java/com/musicboxd/api/accounts/AuthFlowTest.java`, `api/src/test/java/com/musicboxd/api/security/SecurityRulesTest.java`.

**Deploy wiring + verification (Task 5)**
- Modify `deploy/docker-compose.yml`, `deploy/docker-compose.prod.yml`, `deploy/prod.env.example`.
- Modify `deploy/tests/test-compose-config.sh`, `deploy/tests/test-backup-restore.sh`.
- Create `docs/runbooks/runbook-mbd-17-auth.md`.

---

### Task 1: Persistence foundation and the `profiles` stub

**Files:**
- Modify: `api/build.gradle.kts`
- Modify: `api/src/main/resources/application.yml`
- Create: `api/src/main/java/com/musicboxd/api/db/ModuleFlyway.java`
- Create: `api/src/main/java/com/musicboxd/api/db/Constraints.java`
- Create: `api/src/main/resources/db/migration/profiles/V1__create_profiles.sql`
- Create: `api/src/main/java/com/musicboxd/api/profiles/ProfilesConfig.java`
- Create: `api/src/main/java/com/musicboxd/api/profiles/ProfileRepository.java`
- Create: `api/src/main/java/com/musicboxd/api/profiles/ProfilesApi.java`
- Create: `api/src/main/java/com/musicboxd/api/profiles/UsernameTakenException.java`
- Create: `api/src/test/java/com/musicboxd/api/TestcontainersConfiguration.java`
- Modify: `api/src/test/java/com/musicboxd/api/demo/DemoOpenApiTest.java`
- Modify: `api/src/test/java/com/musicboxd/api/demo/DemoRateLimitControllerTest.java`
- Test: `api/src/test/java/com/musicboxd/api/profiles/ProfilesApiTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces:
  - `com.musicboxd.api.db.ModuleFlyway.forSchema(DataSource dataSource, String schema): Flyway`
  - `com.musicboxd.api.db.Constraints.violated(DataAccessException e, String constraint): boolean`
  - `com.musicboxd.api.profiles.ProfilesApi` (Spring bean): `void createProfile(UUID accountId, String username)` (throws `UsernameTakenException`), `Optional<String> findUsername(UUID accountId)`
  - `com.musicboxd.api.profiles.UsernameTakenException extends ErrorResponseException` (409)
  - Test helper `com.musicboxd.api.TestcontainersConfiguration` — every `@SpringBootTest` adds `@Import(TestcontainersConfiguration.class)`.
  - Table `profiles.profiles(id uuid PK, username text, created_at timestamptz)`, unique index `profiles_username_key` on `lower(username)`.

- [ ] **Step 1: Add dependencies**

In `api/build.gradle.kts`, replace the `dependencies { ... }` block with:

```kotlin
dependencies {
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-api:3.1.1")
	// Per-user rate limiting (AD-10): token buckets, held in a bounded per-policy cache.
	implementation("com.bucket4j:bucket4j_jdk17-core:8.21.0")
	implementation("com.github.ben-manes.caffeine:caffeine")
	// Persistence: plain JDBC, one Flyway instance per module schema (spine: Migrations).
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.flywaydb:flyway-database-postgresql")
	// Compile scope, not runtimeOnly: Constraints reads PSQLException's constraint name.
	implementation("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
```

Note: Boot 4.1 manages Testcontainers 2.x, whose artifacts are `testcontainers-postgresql` / `testcontainers-junit-jupiter` and whose class is `org.testcontainers.postgresql.PostgreSQLContainer`. If `./gradlew dependencies --configuration testRuntimeClasspath` shows Testcontainers 1.x instead, use artifacts `org.testcontainers:postgresql` / `org.testcontainers:junit-jupiter` and class `org.testcontainers.containers.PostgreSQLContainer<?>`.

- [ ] **Step 2: Configure the datasource**

In `api/src/main/resources/application.yml`, replace the `spring:` block with:

```yaml
spring:
  application:
    name: musicboxd-api
  datasource:
    # Compose hands the api the same postgres.env the database reads (AD-11: secrets in env files on the host).
    url: jdbc:postgresql://${POSTGRES_HOST:postgres}:${POSTGRES_PORT:5432}/${POSTGRES_DB:musicboxd}
    username: ${POSTGRES_USER:musicboxd}
    password: ${POSTGRES_PASSWORD:}
  flyway:
    # Boot's single Flyway is off: each module migrates its own schema (see db.ModuleFlyway).
    enabled: false
```

- [ ] **Step 3: Add the Testcontainers config and wire existing Spring Boot tests to it**

Create `api/src/test/java/com/musicboxd/api/TestcontainersConfiguration.java`:

```java
package com.musicboxd.api;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real Postgres 18 (the production major, see the spine's Stack) for every
 * {@code @SpringBootTest}. {@code @ServiceConnection} overrides the datasource settings
 * in application.yml. Needs a running Docker daemon.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgres() {
		return new PostgreSQLContainer("postgres:18");
	}
}
```

In `api/src/test/java/com/musicboxd/api/demo/DemoOpenApiTest.java` add the import lines and annotation:

```java
import org.springframework.context.annotation.Import;

import com.musicboxd.api.TestcontainersConfiguration;
```

```java
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DemoOpenApiTest {
```

In `api/src/test/java/com/musicboxd/api/demo/DemoRateLimitControllerTest.java` add the same two imports and annotation:

```java
@SpringBootTest(properties = {
	"musicboxd.rate-limit.policies.demo.capacity=2",
	"musicboxd.rate-limit.policies.demo.refill-period=1h" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DemoRateLimitControllerTest {
```

- [ ] **Step 4: Run the existing suite to confirm the foundation compiles and still passes**

Run (bash, from repo root, Docker running): `cd api && ./gradlew test`
Expected: BUILD SUCCESSFUL, all existing tests pass.

- [ ] **Step 5: Write the failing profiles tests**

Create `api/src/test/java/com/musicboxd/api/profiles/ProfilesApiTest.java`:

```java
package com.musicboxd.api.profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import com.musicboxd.api.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProfilesApiTest {

	@Autowired
	private ProfilesApi profiles;

	@Test
	void createdProfileUsernameIsReadableBack() {
		var id = UUID.randomUUID();
		String username = uniqueUsername();
		profiles.createProfile(id, username);
		assertThat(profiles.findUsername(id)).contains(username);
	}

	@Test
	void unknownAccountHasNoUsername() {
		assertThat(profiles.findUsername(UUID.randomUUID())).isEmpty();
	}

	@Test
	void usernameUniquenessIgnoresCase() {
		String username = uniqueUsername();
		profiles.createProfile(UUID.randomUUID(), username);
		assertThatThrownBy(() -> profiles.createProfile(UUID.randomUUID(), username.toUpperCase()))
			.isInstanceOf(UsernameTakenException.class);
	}

	@Test
	void sameAccountTwiceIsNotReportedAsUsernameTaken() {
		var id = UUID.randomUUID();
		profiles.createProfile(id, uniqueUsername());
		// Primary-key clash is a programming error, not a 409 for the user.
		assertThatThrownBy(() -> profiles.createProfile(id, uniqueUsername()))
			.isInstanceOf(DuplicateKeyException.class)
			.isNotInstanceOf(UsernameTakenException.class);
	}

	@Test
	void databaseRejectsUsernameOutsideTheAllowedShape() {
		// The HTTP edge validates first (Task 4); the CHECK is the backstop.
		assertThatThrownBy(() -> profiles.createProfile(UUID.randomUUID(), "has space"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> profiles.createProfile(UUID.randomUUID(), "ab"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private static String uniqueUsername() {
		return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
```

- [ ] **Step 6: Run to verify it fails**

Run: `cd api && ./gradlew test --tests com.musicboxd.api.profiles.ProfilesApiTest`
Expected: FAIL — compilation error, `ProfilesApi` / `UsernameTakenException` not found.

- [ ] **Step 7: Implement the shared db helpers**

Create `api/src/main/java/com/musicboxd/api/db/ModuleFlyway.java`:

```java
package com.musicboxd.api.db;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

/**
 * One Flyway instance per module schema (spine: Migrations). A module's migrations live in
 * {@code db/migration/<schema>} and its history table lives in the schema it tracks, so
 * modules never share or order each other's migrations.
 */
public final class ModuleFlyway {

	private ModuleFlyway() {
	}

	public static Flyway forSchema(DataSource dataSource, String schema) {
		return Flyway.configure()
			.dataSource(dataSource)
			.schemas(schema)
			.defaultSchema(schema)
			.createSchemas(true)
			.locations("classpath:db/migration/" + schema)
			.load();
	}
}
```

Create `api/src/main/java/com/musicboxd/api/db/Constraints.java`:

```java
package com.musicboxd.api.db;

import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;

/** Tells which Postgres constraint (or unique index) a failed write ran into. */
public final class Constraints {

	private Constraints() {
	}

	public static boolean violated(DataAccessException e, String constraint) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof PSQLException psql && psql.getServerErrorMessage() != null
					&& constraint.equals(psql.getServerErrorMessage().getConstraint())) {
				return true;
			}
		}
		return false;
	}
}
```

- [ ] **Step 8: Add the profiles migration**

Create `api/src/main/resources/db/migration/profiles/V1__create_profiles.sql`:

```sql
-- Minimal profiles stub (MBD-17): username is owned here, not by accounts (AD-13).
-- epic-perfil-favoritos extends this table (avatar, cover, bio, genres, favorites); never recreate it.
-- id equals the accounts.accounts id; no cross-schema foreign key (AD-1).
CREATE TABLE profiles.profiles (
	id         uuid        PRIMARY KEY,
	username   text        NOT NULL CHECK (username ~ '^[A-Za-z0-9_]{3,20}$'),
	created_at timestamptz NOT NULL DEFAULT now()
);

-- Case-insensitive uniqueness; the stored value keeps the casing the person chose.
CREATE UNIQUE INDEX profiles_username_key ON profiles.profiles (lower(username));
```

- [ ] **Step 9: Implement the profiles module**

Create `api/src/main/java/com/musicboxd/api/profiles/ProfilesConfig.java`:

```java
package com.musicboxd.api.profiles;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.musicboxd.api.db.ModuleFlyway;

@Configuration
class ProfilesConfig {

	@Bean(initMethod = "migrate")
	Flyway profilesFlyway(DataSource dataSource) {
		return ModuleFlyway.forSchema(dataSource, "profiles");
	}
}
```

Create `api/src/main/java/com/musicboxd/api/profiles/ProfileRepository.java`:

```java
package com.musicboxd.api.profiles;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Package-private: other modules go through {@link ProfilesApi} (AD-1). */
@Repository
class ProfileRepository {

	static final String USERNAME_UNIQUE = "profiles_username_key";

	private final JdbcClient jdbc;

	ProfileRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(UUID id, String username) {
		jdbc.sql("INSERT INTO profiles.profiles (id, username) VALUES (:id, :username)")
			.param("id", id)
			.param("username", username)
			.update();
	}

	Optional<String> findUsername(UUID id) {
		return jdbc.sql("SELECT username FROM profiles.profiles WHERE id = :id")
			.param("id", id)
			.query(String.class)
			.optional();
	}
}
```

Create `api/src/main/java/com/musicboxd/api/profiles/UsernameTakenException.java`:

```java
package com.musicboxd.api.profiles;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class UsernameTakenException extends ErrorResponseException {

	public UsernameTakenException() {
		super(HttpStatus.CONFLICT,
				ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Username is already taken"), null);
	}
}
```

Create `api/src/main/java/com/musicboxd/api/profiles/ProfilesApi.java`:

```java
package com.musicboxd.api.profiles;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.musicboxd.api.db.Constraints;

/**
 * The profiles module's public API (AD-1). Owns display identity, starting with the
 * username (AD-13); other modules never touch the profiles schema directly.
 */
@Service
public class ProfilesApi {

	private final ProfileRepository profiles;

	ProfilesApi(ProfileRepository profiles) {
		this.profiles = profiles;
	}

	/** Joins the caller's transaction, so registration creates account and profile atomically. */
	@Transactional
	public void createProfile(UUID accountId, String username) {
		try {
			profiles.insert(accountId, username);
		}
		catch (DuplicateKeyException e) {
			if (Constraints.violated(e, ProfileRepository.USERNAME_UNIQUE)) {
				throw new UsernameTakenException();
			}
			throw e;
		}
	}

	@Transactional(readOnly = true)
	public Optional<String> findUsername(UUID accountId) {
		return profiles.findUsername(accountId);
	}
}
```

- [ ] **Step 10: Run the profiles tests and the full suite**

Run: `cd api && ./gradlew test`
Expected: BUILD SUCCESSFUL; `ProfilesApiTest` 5 tests pass; existing tests still pass.

- [ ] **Step 11: Commit**

```bash
git add api/build.gradle.kts api/src/main/resources/application.yml api/src/main/java/com/musicboxd/api/db api/src/main/java/com/musicboxd/api/profiles api/src/main/resources/db/migration/profiles api/src/test/java/com/musicboxd/api/TestcontainersConfiguration.java api/src/test/java/com/musicboxd/api/profiles api/src/test/java/com/musicboxd/api/demo
git commit -m "Add Postgres persistence with per-module Flyway and the profiles username stub (MBD-17)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `accounts` schema, registration, and credential checks

**Files:**
- Modify: `api/build.gradle.kts`
- Create: `api/src/main/resources/db/migration/accounts/V1__create_accounts.sql`
- Create: `api/src/main/java/com/musicboxd/api/accounts/AccountsConfig.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/Account.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/AccountRepository.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/AccountView.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/AccountService.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/EmailTakenException.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/PasswordTooLongException.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/InvalidCredentialsException.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/AccountNotFoundException.java`
- Test: `api/src/test/java/com/musicboxd/api/accounts/AccountServiceTest.java`

**Interfaces:**
- Consumes: `ProfilesApi.createProfile(UUID, String)`, `ProfilesApi.findUsername(UUID)`, `UsernameTakenException`, `Constraints.violated(...)`, `ModuleFlyway.forSchema(...)`, `TestcontainersConfiguration` (Task 1).
- Produces:
  - `public record AccountView(UUID id, String email, String username)`
  - `AccountService` (Spring bean): `AccountView register(String email, String password, String username)`; `UUID authenticate(String email, String password)`; `AccountView describe(UUID accountId)`
  - Exceptions (all `ErrorResponseException`): `EmailTakenException` 409, `PasswordTooLongException` 400, `InvalidCredentialsException` 401, `AccountNotFoundException` 404.
  - Table `accounts.accounts(id uuid PK, email text, password_hash text, created_at timestamptz)`, unique index `accounts_email_key` on `lower(email)`.

- [ ] **Step 1: Add the password-hashing dependency**

In `api/build.gradle.kts`, after the `org.postgresql:postgresql` line, add:

```kotlin
	// BCrypt (AD-8). Only the crypto module: the full security starter arrives with the filter chain in Task 4.
	implementation("org.springframework.security:spring-security-crypto")
```

- [ ] **Step 2: Write the failing service tests**

Create `api/src/test/java/com/musicboxd/api/accounts/AccountServiceTest.java`:

```java
package com.musicboxd.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.profiles.ProfilesApi;
import com.musicboxd.api.profiles.UsernameTakenException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountServiceTest {

	private static final String PASSWORD = "correct-horse";

	@Autowired
	private AccountService accounts;

	@Autowired
	private ProfilesApi profiles;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void registrationStoresABcryptHashAndPutsTheUsernameInProfiles() {
		String email = uniqueEmail();
		String username = uniqueUsername();
		AccountView view = accounts.register(email, PASSWORD, username);

		assertThat(view.email()).isEqualTo(email);
		assertThat(view.username()).isEqualTo(username);
		String hash = jdbc.sql("SELECT password_hash FROM accounts.accounts WHERE id = :id")
			.param("id", view.id()).query(String.class).single();
		assertThat(hash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
		assertThat(profiles.findUsername(view.id())).contains(username);
	}

	@Test
	void accountsSchemaHasNoUsernameColumn() {
		Integer columns = jdbc.sql("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'accounts' AND column_name = 'username'""")
			.query(Integer.class).single();
		assertThat(columns).isZero();
	}

	@Test
	void emailIsTrimmedAndLowercased() {
		String email = uniqueEmail();
		AccountView view = accounts.register("  " + email.toUpperCase() + " ", PASSWORD, uniqueUsername());
		assertThat(view.email()).isEqualTo(email);
		assertThat(accounts.authenticate(email.toUpperCase(), PASSWORD)).isEqualTo(view.id());
	}

	@Test
	void duplicateEmailDifferingOnlyInCaseIsRejected() {
		String email = uniqueEmail();
		accounts.register(email, PASSWORD, uniqueUsername());
		assertThatThrownBy(() -> accounts.register(email.toUpperCase(), PASSWORD, uniqueUsername()))
			.isInstanceOf(EmailTakenException.class);
	}

	@Test
	void takenUsernameRollsBackTheAccount() {
		String username = uniqueUsername();
		accounts.register(uniqueEmail(), PASSWORD, username);

		String email = uniqueEmail();
		assertThatThrownBy(() -> accounts.register(email, PASSWORD, username.toUpperCase()))
			.isInstanceOf(UsernameTakenException.class);
		Integer orphans = jdbc.sql("SELECT count(*) FROM accounts.accounts WHERE email = :email")
			.param("email", email).query(Integer.class).single();
		assertThat(orphans).isZero();
		// The email is still free for a retry with another username.
		assertThat(accounts.register(email, PASSWORD, uniqueUsername()).email()).isEqualTo(email);
	}

	@Test
	void passwordOverBcryptByteLimitIsRejectedEvenWhenUnder72Characters() {
		String twoByteChars = "é".repeat(37); // 37 chars, 74 UTF-8 bytes
		assertThatThrownBy(() -> accounts.register(uniqueEmail(), twoByteChars, uniqueUsername()))
			.isInstanceOf(PasswordTooLongException.class);
	}

	@Test
	void correctPasswordAuthenticates() {
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());
		assertThat(accounts.authenticate(email, PASSWORD)).isEqualTo(view.id());
	}

	@Test
	void wrongPasswordUnknownEmailAndOverlongPasswordAllFailTheSameWay() {
		String email = uniqueEmail();
		accounts.register(email, PASSWORD, uniqueUsername());
		assertThatThrownBy(() -> accounts.authenticate(email, "wrong-password"))
			.isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> accounts.authenticate(uniqueEmail(), PASSWORD))
			.isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> accounts.authenticate(email, "x".repeat(73)))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void describeJoinsEmailFromAccountsAndUsernameFromProfiles() {
		String email = uniqueEmail();
		String username = uniqueUsername();
		AccountView registered = accounts.register(email, PASSWORD, username);
		assertThat(accounts.describe(registered.id())).isEqualTo(registered);
	}

	@Test
	void describeUnknownAccountIsNotFound() {
		assertThatThrownBy(() -> accounts.describe(UUID.randomUUID()))
			.isInstanceOf(AccountNotFoundException.class);
	}

	static String uniqueEmail() {
		return "u" + UUID.randomUUID().toString().replace("-", "") + "@example.com";
	}

	static String uniqueUsername() {
		return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `cd api && ./gradlew test --tests com.musicboxd.api.accounts.AccountServiceTest`
Expected: FAIL — compilation errors (`AccountService`, `AccountView`, exceptions not found).

- [ ] **Step 4: Add the accounts migration**

Create `api/src/main/resources/db/migration/accounts/V1__create_accounts.sql`:

```sql
-- Credentials only. The username lives in profiles.profiles (AD-13).
CREATE TABLE accounts.accounts (
	id            uuid        PRIMARY KEY,
	email         text        NOT NULL,
	password_hash text        NOT NULL,
	created_at    timestamptz NOT NULL DEFAULT now()
);

-- Emails are stored lowercased by AccountService; the index guarantees it regardless.
CREATE UNIQUE INDEX accounts_email_key ON accounts.accounts (lower(email));
```

- [ ] **Step 5: Implement config, record types, and exceptions**

Create `api/src/main/java/com/musicboxd/api/accounts/AccountsConfig.java`:

```java
package com.musicboxd.api.accounts;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.musicboxd.api.db.ModuleFlyway;

@Configuration
class AccountsConfig {

	@Bean(initMethod = "migrate")
	Flyway accountsFlyway(DataSource dataSource) {
		return ModuleFlyway.forSchema(dataSource, "accounts");
	}

	/** BCrypt today (AD-8); the stored {bcrypt} prefix keeps a later switch to Argon2 possible. */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}
}
```

Create `api/src/main/java/com/musicboxd/api/accounts/Account.java`:

```java
package com.musicboxd.api.accounts;

import java.util.UUID;

record Account(UUID id, String email, String passwordHash) {
}
```

Create `api/src/main/java/com/musicboxd/api/accounts/AccountView.java`:

```java
package com.musicboxd.api.accounts;

import java.util.UUID;

/** What the API shows about an account: never the password hash. */
public record AccountView(UUID id, String email, String username) {
}
```

Create `api/src/main/java/com/musicboxd/api/accounts/EmailTakenException.java`:

```java
package com.musicboxd.api.accounts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class EmailTakenException extends ErrorResponseException {

	public EmailTakenException() {
		super(HttpStatus.CONFLICT,
				ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "An account with this email already exists"), null);
	}
}
```

Create `api/src/main/java/com/musicboxd/api/accounts/PasswordTooLongException.java`:

```java
package com.musicboxd.api.accounts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class PasswordTooLongException extends ErrorResponseException {

	public PasswordTooLongException() {
		super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"Password must be at most " + AccountService.MAX_PASSWORD_BYTES + " bytes"), null);
	}
}
```

Create `api/src/main/java/com/musicboxd/api/accounts/InvalidCredentialsException.java`:

```java
package com.musicboxd.api.accounts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** One message for unknown email and wrong password, so login does not reveal which emails exist. */
public class InvalidCredentialsException extends ErrorResponseException {

	public InvalidCredentialsException() {
		super(HttpStatus.UNAUTHORIZED,
				ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password"), null);
	}
}
```

Create `api/src/main/java/com/musicboxd/api/accounts/AccountNotFoundException.java`:

```java
package com.musicboxd.api.accounts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class AccountNotFoundException extends ErrorResponseException {

	public AccountNotFoundException() {
		super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Account not found"), null);
	}
}
```

- [ ] **Step 6: Implement the repository**

Create `api/src/main/java/com/musicboxd/api/accounts/AccountRepository.java`:

```java
package com.musicboxd.api.accounts;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AccountRepository {

	static final String EMAIL_UNIQUE = "accounts_email_key";

	private final JdbcClient jdbc;

	AccountRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(Account account) {
		jdbc.sql("INSERT INTO accounts.accounts (id, email, password_hash) VALUES (:id, :email, :hash)")
			.param("id", account.id())
			.param("email", account.email())
			.param("hash", account.passwordHash())
			.update();
	}

	Optional<Account> findByEmail(String email) {
		return jdbc.sql("SELECT id, email, password_hash FROM accounts.accounts WHERE lower(email) = lower(:email)")
			.param("email", email)
			.query(Account.class)
			.optional();
	}

	Optional<Account> findById(UUID id) {
		return jdbc.sql("SELECT id, email, password_hash FROM accounts.accounts WHERE id = :id")
			.param("id", id)
			.query(Account.class)
			.optional();
	}
}
```

- [ ] **Step 7: Implement the service**

Create `api/src/main/java/com/musicboxd/api/accounts/AccountService.java`:

```java
package com.musicboxd.api.accounts;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.musicboxd.api.db.Constraints;
import com.musicboxd.api.profiles.ProfilesApi;

@Service
public class AccountService {

	/** BCrypt reads only 72 bytes, and Spring Security rejects longer input with an exception. */
	static final int MAX_PASSWORD_BYTES = 72;

	private final AccountRepository accounts;
	private final ProfilesApi profiles;
	private final PasswordEncoder passwordEncoder;
	/** Checked when the email is unknown, so a miss costs the same hash as a wrong password. */
	private final String dummyHash;

	AccountService(AccountRepository accounts, ProfilesApi profiles, PasswordEncoder passwordEncoder) {
		this.accounts = accounts;
		this.profiles = profiles;
		this.passwordEncoder = passwordEncoder;
		this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	/** Creates the account and its profile in one transaction: both or neither. */
	@Transactional
	public AccountView register(String email, String password, String username) {
		if (tooLong(password)) {
			throw new PasswordTooLongException();
		}
		var account = new Account(UUID.randomUUID(), normalize(email), passwordEncoder.encode(password));
		try {
			accounts.insert(account);
		}
		catch (DuplicateKeyException e) {
			if (Constraints.violated(e, AccountRepository.EMAIL_UNIQUE)) {
				throw new EmailTakenException();
			}
			throw e;
		}
		profiles.createProfile(account.id(), username);
		return new AccountView(account.id(), account.email(), username);
	}

	/** Returns the account id, or throws {@link InvalidCredentialsException} without saying why. */
	@Transactional(readOnly = true)
	public UUID authenticate(String email, String password) {
		if (tooLong(password)) {
			throw new InvalidCredentialsException();
		}
		var account = accounts.findByEmail(normalize(email));
		String hash = account.map(Account::passwordHash).orElse(dummyHash);
		if (!passwordEncoder.matches(password, hash) || account.isEmpty()) {
			throw new InvalidCredentialsException();
		}
		return account.get().id();
	}

	@Transactional(readOnly = true)
	public AccountView describe(UUID accountId) {
		var account = accounts.findById(accountId).orElseThrow(AccountNotFoundException::new);
		String username = profiles.findUsername(accountId).orElseThrow(AccountNotFoundException::new);
		return new AccountView(accountId, account.email(), username);
	}

	private static String normalize(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private static boolean tooLong(String password) {
		return password.getBytes(UTF_8).length > MAX_PASSWORD_BYTES;
	}
}
```

- [ ] **Step 8: Run the tests**

Run: `cd api && ./gradlew test`
Expected: BUILD SUCCESSFUL; `AccountServiceTest` 10 tests pass; all earlier tests still pass.

- [ ] **Step 9: Commit**

```bash
git add api/build.gradle.kts api/src/main/resources/db/migration/accounts api/src/main/java/com/musicboxd/api/accounts api/src/test/java/com/musicboxd/api/accounts
git commit -m "Add accounts schema, BCrypt registration and credential checks (MBD-17)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: JWT access tokens

**Files:**
- Modify: `api/build.gradle.kts`
- Modify: `api/src/main/resources/application.yml`
- Create: `api/src/main/java/com/musicboxd/api/security/AuthProperties.java`
- Create: `api/src/main/java/com/musicboxd/api/security/JwtConfig.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/TokenService.java`
- Create: `api/src/test/resources/config/application.yml`
- Test: `api/src/test/java/com/musicboxd/api/accounts/TokenServiceTest.java`
- Test: `api/src/test/java/com/musicboxd/api/security/AuthPropertiesTest.java`

**Interfaces:**
- Consumes: nothing from Task 2 at the code level (`TokenService` takes a `UUID`).
- Produces:
  - `public record AuthProperties(String jwtSecret, Duration accessTokenTtl)` bound from `musicboxd.auth.*`; `SecretKey signingKey()`. Constructor throws `IllegalStateException` for a blank, non-Base64, or under-32-byte secret.
  - `JwtConfig` beans: `public JwtEncoder jwtEncoder(AuthProperties)`, `public JwtDecoder jwtDecoder(AuthProperties)`, `public Clock clock()`; constant `JwtConfig.ISSUER = "musicboxd"`.
  - `TokenService` (Spring bean): `AccessToken issue(UUID accountId)`; `public record TokenService.AccessToken(String value, long expiresInSeconds)`.
  - JWT shape: HS256; claims `iss=musicboxd`, `sub=<account uuid>`, `iat`, `exp=iat+15m`.

- [ ] **Step 1: Add the JOSE dependency and the test secret**

In `api/build.gradle.kts`, after the `spring-security-crypto` line, add:

```kotlin
	// JWT encode/decode (Nimbus). Brought in alone so Boot's web security auto-config stays off until Task 4.
	implementation("org.springframework.security:spring-security-oauth2-jose")
```

Create `api/src/test/resources/config/application.yml`:

```yaml
# Test-only overrides. Spring Boot reads classpath:/config/application.yml after
# classpath:/application.yml and lets it win, so main config stays the single source.
musicboxd:
  auth:
    # Base64 of "test-only-jwt-secret-not-for-prod-use!!" (39 bytes).
    jwt-secret: dGVzdC1vbmx5LWp3dC1zZWNyZXQtbm90LWZvci1wcm9kLXVzZSEh
```

- [ ] **Step 2: Write the failing tests**

Create `api/src/test/java/com/musicboxd/api/security/AuthPropertiesTest.java`:

```java
package com.musicboxd.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class AuthPropertiesTest {

	private static final Duration TTL = Duration.ofMinutes(15);

	@Test
	void missingSecretFailsStartupWithTheEnvVarName() {
		assertThatThrownBy(() -> new AuthProperties("", TTL))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("MUSICBOXD_JWT_SECRET");
		assertThatThrownBy(() -> new AuthProperties(null, TTL)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void nonBase64SecretIsRejected() {
		assertThatThrownBy(() -> new AuthProperties("not base64 !!!", TTL))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void secretShorterThan32BytesIsRejected() {
		String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);
		assertThatThrownBy(() -> new AuthProperties(sixteenBytes, TTL))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("32 bytes");
	}

	@Test
	void validSecretGivesAnHmacSha256Key() {
		String secret = Base64.getEncoder().encodeToString(new byte[32]);
		assertThat(new AuthProperties(secret, TTL).signingKey().getAlgorithm()).isEqualTo("HmacSHA256");
	}
}
```

Create `api/src/test/java/com/musicboxd/api/accounts/TokenServiceTest.java`:

```java
package com.musicboxd.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import com.musicboxd.api.security.AuthProperties;
import com.musicboxd.api.security.JwtConfig;

class TokenServiceTest {

	private static final String SECRET = "dGVzdC1vbmx5LWp3dC1zZWNyZXQtbm90LWZvci1wcm9kLXVzZSEh";

	private final AuthProperties props = new AuthProperties(SECRET, Duration.ofMinutes(15));
	private final JwtConfig jwt = new JwtConfig();
	private final JwtDecoder decoder = jwt.jwtDecoder(props);

	@Test
	void tokenCarriesTheAccountIdAndExpiresInFifteenMinutes() {
		var accountId = UUID.randomUUID();
		var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		var token = serviceAt(now, props).issue(accountId);
		Jwt decoded = decoder.decode(token.value());

		assertThat(decoded.getSubject()).isEqualTo(accountId.toString());
		assertThat(decoded.getClaimAsString("iss")).isEqualTo(JwtConfig.ISSUER);
		assertThat(decoded.getIssuedAt()).isEqualTo(now);
		assertThat(decoded.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(15)));
		assertThat(token.expiresInSeconds()).isEqualTo(900);
	}

	@Test
	void expiredTokenIsRejected() {
		var token = serviceAt(Instant.now().minus(Duration.ofHours(1)), props).issue(UUID.randomUUID());
		assertThatThrownBy(() -> decoder.decode(token.value())).isInstanceOf(JwtValidationException.class);
	}

	@Test
	void tokenSignedWithAnotherSecretIsRejected() {
		String other = Base64.getEncoder()
			.encodeToString("another-secret-of-at-least-32-bytes!!".getBytes(StandardCharsets.UTF_8));
		var foreign = serviceAt(Instant.now(), new AuthProperties(other, Duration.ofMinutes(15)))
			.issue(UUID.randomUUID());
		assertThatThrownBy(() -> decoder.decode(foreign.value())).isInstanceOf(JwtException.class);
	}

	private TokenService serviceAt(Instant now, AuthProperties signingProps) {
		return new TokenService(jwt.jwtEncoder(signingProps), signingProps, Clock.fixed(now, ZoneOffset.UTC));
	}
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `cd api && ./gradlew test --tests com.musicboxd.api.accounts.TokenServiceTest --tests com.musicboxd.api.security.AuthPropertiesTest`
Expected: FAIL — compilation errors (`AuthProperties`, `JwtConfig`, `TokenService` not found).

- [ ] **Step 4: Implement `AuthProperties` and `JwtConfig`**

Create `api/src/main/java/com/musicboxd/api/security/AuthProperties.java`:

```java
package com.musicboxd.api.security;

import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param jwtSecret Base64 of at least 32 random bytes (HS256); generate with
 *        {@code openssl rand -base64 32}. Validated here so a bad secret stops startup.
 * @param accessTokenTtl access-token lifetime, about 15 minutes (AD-8)
 */
@ConfigurationProperties("musicboxd.auth")
public record AuthProperties(String jwtSecret, @DefaultValue("15m") Duration accessTokenTtl) {

	private static final int MIN_SECRET_BYTES = 32;

	public AuthProperties {
		if (jwtSecret == null || jwtSecret.isBlank()) {
			throw new IllegalStateException("musicboxd.auth.jwt-secret (env MUSICBOXD_JWT_SECRET) must be set");
		}
		if (decode(jwtSecret).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException(
					"musicboxd.auth.jwt-secret must decode to at least " + MIN_SECRET_BYTES + " bytes");
		}
	}

	public SecretKey signingKey() {
		return new SecretKeySpec(decode(jwtSecret), "HmacSHA256");
	}

	private static byte[] decode(String secret) {
		try {
			return Base64.getDecoder().decode(secret.strip());
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException("musicboxd.auth.jwt-secret must be Base64", e);
		}
	}
}
```

Create `api/src/main/java/com/musicboxd/api/security/JwtConfig.java`:

```java
package com.musicboxd.api.security;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Access tokens are HS256 JWTs (AD-8): the API is the only issuer and the only verifier,
 * so one shared secret is enough and no key distribution is needed.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {

	public static final String ISSUER = "musicboxd";

	@Bean
	public JwtEncoder jwtEncoder(AuthProperties props) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(props.signingKey()));
	}

	@Bean
	public JwtDecoder jwtDecoder(AuthProperties props) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(props.signingKey())
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
		// Default validators check exp/nbf (60 s skew); add the issuer.
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		return decoder;
	}

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
```

- [ ] **Step 5: Implement `TokenService`**

Create `api/src/main/java/com/musicboxd/api/accounts/TokenService.java`:

```java
package com.musicboxd.api.accounts;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.musicboxd.api.security.AuthProperties;
import com.musicboxd.api.security.JwtConfig;

/** Issues short-lived access tokens (AD-8). Refresh tokens are MBD-19. */
@Service
public class TokenService {

	public record AccessToken(String value, long expiresInSeconds) {
	}

	private final JwtEncoder encoder;
	private final AuthProperties props;
	private final Clock clock;

	TokenService(JwtEncoder encoder, AuthProperties props, Clock clock) {
		this.encoder = encoder;
		this.props = props;
		this.clock = clock;
	}

	public AccessToken issue(UUID accountId) {
		Instant now = clock.instant();
		var claims = JwtClaimsSet.builder()
			.issuer(JwtConfig.ISSUER)
			.subject(accountId.toString())
			.issuedAt(now)
			.expiresAt(now.plus(props.accessTokenTtl()))
			.build();
		var header = JwsHeader.with(MacAlgorithm.HS256).build();
		String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new AccessToken(value, props.accessTokenTtl().toSeconds());
	}
}
```

- [ ] **Step 6: Configure the auth properties**

In `api/src/main/resources/application.yml`, replace the `musicboxd:` block at the bottom with:

```yaml
musicboxd:
  # AD-10 layer 2: named per-user limit policies, applied with @RateLimited(policy = "...").
  rate-limit:
    policies:
      demo:
        capacity: 5
        refill-period: 1m
  auth:
    # Base64 of >= 32 random bytes (openssl rand -base64 32), from /etc/musicboxd/api.env.
    # No default on purpose: the api refuses to start without it.
    jwt-secret: ${MUSICBOXD_JWT_SECRET:}
    access-token-ttl: 15m
```

(Move the existing `# AD-10 layer 2...` comment under `musicboxd:` as shown; delete the old copy above it.)

- [ ] **Step 7: Run the tests and the full suite**

Run: `cd api && ./gradlew test`
Expected: BUILD SUCCESSFUL; `TokenServiceTest` 3 and `AuthPropertiesTest` 4 pass; every earlier test still passes. If an earlier MockMvc test now gets 401, Boot's web security auto-config was pulled in by the JOSE jar — stop and check `./gradlew dependencies` for `spring-boot-security`/`spring-security-web`; this task must not add them.

- [ ] **Step 8: Commit**

```bash
git add api/build.gradle.kts api/src/main/resources/application.yml api/src/main/java/com/musicboxd/api/security api/src/main/java/com/musicboxd/api/accounts/TokenService.java api/src/test/resources/config/application.yml api/src/test/java/com/musicboxd/api/security api/src/test/java/com/musicboxd/api/accounts/TokenServiceTest.java
git commit -m "Issue 15-minute HS256 access tokens from an env-provided secret (MBD-17)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: HTTP endpoints and the security filter chain

**Files:**
- Modify: `api/build.gradle.kts`
- Create: `api/src/main/java/com/musicboxd/api/security/SecurityConfig.java`
- Create: `api/src/main/java/com/musicboxd/api/security/OpenApiConfig.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/AuthController.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/AccountController.java`
- Create: `api/src/main/java/com/musicboxd/api/accounts/AccountsExceptionHandler.java`
- Modify: `api/src/test/java/com/musicboxd/api/health/HealthControllerTest.java`
- Test: `api/src/test/java/com/musicboxd/api/accounts/AuthFlowTest.java`
- Test: `api/src/test/java/com/musicboxd/api/security/SecurityRulesTest.java`

**Interfaces:**
- Consumes: `AccountService.register/authenticate/describe`, `AccountView`, `TokenService.issue`, `TokenService.AccessToken` (Tasks 2-3); `JwtConfig`'s `JwtDecoder`/`JwtEncoder`/`Clock` beans; `AuthProperties` bean.
- Produces (the HTTP contract, in `/api/v1/api-docs`):
  - `POST /api/v1/auth/register` body `{"email","password","username"}` → `201` `{"id","email","username"}`; `400` invalid input; `409` email or username taken.
  - `POST /api/v1/auth/login` body `{"email","password"}` → `200` `{"accessToken","tokenType":"Bearer","expiresIn":900}`; `401` invalid credentials.
  - `GET /api/v1/accounts/me` with `Authorization: Bearer <token>` → `200` `{"id","email","username"}`; `401` without a valid token.
  - Security rules: those two POSTs public; `GET /api/v1/accounts/me` authenticated; every other `GET /api/**` public (AD-7); everything else authenticated. Principal name = account id (the JWT `sub`), which `PrincipalOrIpKeyResolver` already uses as `user:<id>`.
  - OpenAPI security scheme named `bearer` (HTTP bearer, JWT).

- [ ] **Step 1: Add the security starters**

In `api/build.gradle.kts`, replace the `spring-security-oauth2-jose` line (and its comment) with:

```kotlin
	// Spring Security filter chain validating bearer JWTs (AD-8); brings spring-security-oauth2-jose.
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-validation")
```

Note: `spring-boot-starter-security-oauth2-resource-server` is the Boot 4 name. If Gradle cannot resolve it, use the Boot 3 name `spring-boot-starter-oauth2-resource-server`.

- [ ] **Step 2: Write the failing acceptance and security tests**

Create `api/src/test/java/com/musicboxd/api/accounts/AuthFlowTest.java`:

```java
package com.musicboxd.api.accounts;

import static com.musicboxd.api.accounts.AccountServiceTest.uniqueEmail;
import static com.musicboxd.api.accounts.AccountServiceTest.uniqueUsername;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.security.AuthProperties;

/** MBD-17 acceptance, over HTTP: register, log in, call the protected endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthFlowTest {

	private static final String PASSWORD = "correct-horse";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private AuthProperties authProperties;

	@Test
	void newPersonRegistersLogsInAndCallsTheProtectedEndpoint() throws Exception {
		String email = uniqueEmail();
		String username = uniqueUsername();

		register(email, PASSWORD, username)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isString())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.username").value(username))
			.andExpect(jsonPath("$.password").doesNotExist());

		String body = login(email, PASSWORD)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(body, "$.accessToken");

		mockMvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.username").value(username));
	}

	@Test
	void usernameIsReadFromTheProfilesSchema() throws Exception {
		String username = uniqueUsername();
		String body = register(uniqueEmail(), PASSWORD, username)
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

		String stored = jdbc.sql("SELECT username FROM profiles.profiles WHERE id = :id")
			.param("id", id).query(String.class).single();
		assertThat(stored).isEqualTo(username);
	}

	@Test
	void wrongPasswordAndUnknownEmailGetTheSame401() throws Exception {
		String email = uniqueEmail();
		register(email, PASSWORD, uniqueUsername()).andExpect(status().isCreated());

		String wrongPassword = login(email, "wrong-password")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.status").value(401))
			.andReturn().getResponse().getContentAsString();
		String unknownEmail = login(uniqueEmail(), PASSWORD)
			.andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();
		assertThat(unknownEmail).isEqualTo(wrongPassword);
	}

	@Test
	void protectedEndpointRejectsMissingMalformedAndExpiredTokens() throws Exception {
		mockMvc.perform(get("/api/v1/accounts/me")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer not-a-jwt"))
			.andExpect(status().isUnauthorized());

		var expired = new TokenService(jwtEncoder, authProperties,
				Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC))
			.issue(UUID.randomUUID());
		mockMvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + expired.value()))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void registrationRejectsInvalidInputWithProblemDetails() throws Exception {
		register(uniqueEmail(), "short", uniqueUsername()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(400));
		register(uniqueEmail(), PASSWORD, "ab").andExpect(status().isBadRequest());
		register(uniqueEmail(), PASSWORD, "a".repeat(21)).andExpect(status().isBadRequest());
		register(uniqueEmail(), PASSWORD, "has space").andExpect(status().isBadRequest());
		register(uniqueEmail(), PASSWORD, "hyphen-ated").andExpect(status().isBadRequest());
		register("not-an-email", PASSWORD, uniqueUsername()).andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void boundaryLengthsAreAccepted() throws Exception {
		register(uniqueEmail(), "12345678", uniqueUsername()).andExpect(status().isCreated());
		String twenty = ("U_" + UUID.randomUUID().toString().replace("-", "")).substring(0, 20);
		register(uniqueEmail(), PASSWORD, twenty).andExpect(status().isCreated());
	}

	@Test
	void overlongPasswordIs400OnRegisterAnd401OnLoginNever500() throws Exception {
		String twoByteChars = "é".repeat(37); // 74 UTF-8 bytes
		register(uniqueEmail(), twoByteChars, uniqueUsername()).andExpect(status().isBadRequest());

		String email = uniqueEmail();
		register(email, PASSWORD, uniqueUsername()).andExpect(status().isCreated());
		login(email, twoByteChars).andExpect(status().isUnauthorized());
	}

	@Test
	void duplicateEmailOrUsernameIsAConflict() throws Exception {
		String email = uniqueEmail();
		String username = uniqueUsername();
		register(email, PASSWORD, username).andExpect(status().isCreated());

		register(email.toUpperCase(), PASSWORD, uniqueUsername())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value(409));
		register(uniqueEmail(), PASSWORD, username.toUpperCase()).andExpect(status().isConflict());
	}

	private ResultActions register(String email, String password, String username) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"%s","password":"%s","username":"%s"}""".formatted(email, password, username)));
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"%s","password":"%s"}""".formatted(email, password)));
	}
}
```

Create `api/src/test/java/com/musicboxd/api/security/SecurityRulesTest.java`:

```java
package com.musicboxd.api.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.musicboxd.api.TestcontainersConfiguration;

/** AD-7: public GETs need no token; writes need one; the contract documents the bearer scheme. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityRulesTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void publicGetsNeedNoToken() throws Exception {
		mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/api-docs")).andExpect(status().isOk());
	}

	@Test
	void unauthenticatedWriteIsRejectedBeforeRouting() throws Exception {
		mockMvc.perform(post("/api/v1/health")).andExpect(status().isUnauthorized());
	}

	@Test
	void contractListsAuthRoutesAndTheBearerScheme() throws Exception {
		mockMvc.perform(get("/api/v1/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/register'].post").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['401']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/accounts/me'].get.security[0].bearer").exists())
			.andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"));
	}
}
```

Modify `api/src/test/java/com/musicboxd/api/health/HealthControllerTest.java` so the slice runs under the real rules (add the import and annotation, keep the test body unchanged):

```java
import org.springframework.context.annotation.Import;

import com.musicboxd.api.security.JwtConfig;
import com.musicboxd.api.security.SecurityConfig;
```

```java
@WebMvcTest(HealthController.class)
@Import({ SecurityConfig.class, JwtConfig.class })
class HealthControllerTest {
```

- [ ] **Step 3: Run to verify they fail**

Run: `cd api && ./gradlew test --tests com.musicboxd.api.accounts.AuthFlowTest --tests com.musicboxd.api.security.SecurityRulesTest`
Expected: FAIL — compilation error, `SecurityConfig` not found (from `HealthControllerTest`). That is the expected first failure.

- [ ] **Step 4: Implement the security config and OpenAPI scheme**

Create `api/src/main/java/com/musicboxd/api/security/SecurityConfig.java`:

```java
package com.musicboxd.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.DispatcherType;

/**
 * Route-level gating (AD-7, AD-8): public GETs need no token, every write needs a valid
 * bearer JWT. Roles and /api/v1/admin/** arrive with MBD-21.
 */
@Configuration
public class SecurityConfig {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
		http
			// Stateless bearer tokens, no cookie-borne credentials: CSRF does not apply.
			// MBD-19's refresh cookie (SameSite=Lax, path-scoped) must revisit this.
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				// Error dispatches carry the original status; don't turn them into 401s.
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/accounts/me").authenticated()
				.requestMatchers(HttpMethod.GET, "/api/**").permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()));
		return http.build();
	}
}
```

Create `api/src/main/java/com/musicboxd/api/security/OpenApiConfig.java`:

```java
package com.musicboxd.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;

/** Declares the bearer scheme so the generated TypeScript client knows which calls need a token. */
@Configuration
public class OpenApiConfig {

	public static final String BEARER = "bearer";

	@Bean
	OpenAPI openApi() {
		return new OpenAPI().components(new Components().addSecuritySchemes(BEARER,
				new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")));
	}
}
```

- [ ] **Step 5: Implement the controllers and Problem Details handler**

Create `api/src/main/java/com/musicboxd/api/accounts/AuthController.java`:

```java
package com.musicboxd.api.accounts;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	/** Password min 8 chars; username 3-20 of [A-Za-z0-9_] (user decision, 2026-09-27). */
	public record RegisterRequest(
			@NotBlank @Email @Size(max = 254) String email,
			@NotNull @Size(min = 8, message = "must be at least 8 characters") String password,
			@NotNull @Pattern(regexp = "[A-Za-z0-9_]{3,20}",
					message = "must be 3-20 letters, digits or underscores") String username) {
	}

	public record LoginRequest(@NotBlank String email, @NotBlank String password) {
	}

	public record TokenResponse(String accessToken, String tokenType, long expiresIn) {
	}

	private final AccountService accounts;
	private final TokenService tokens;

	AuthController(AccountService accounts, TokenService tokens) {
		this.accounts = accounts;
		this.tokens = tokens;
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.CREATED)
	@ApiResponse(responseCode = "201", description = "Account and profile created")
	@ApiResponse(responseCode = "400", description = "Invalid email, password or username")
	@ApiResponse(responseCode = "409", description = "Email or username already taken")
	public AccountView register(@Valid @RequestBody RegisterRequest request) {
		return accounts.register(request.email(), request.password(), request.username());
	}

	@PostMapping("/login")
	@ApiResponse(responseCode = "200", description = "A short-lived bearer access token")
	@ApiResponse(responseCode = "401", description = "Unknown email or wrong password (indistinguishable)")
	public TokenResponse login(@Valid @RequestBody LoginRequest request) {
		UUID accountId = accounts.authenticate(request.email(), request.password());
		var token = tokens.issue(accountId);
		return new TokenResponse(token.value(), "Bearer", token.expiresInSeconds());
	}
}
```

Create `api/src/main/java/com/musicboxd/api/accounts/AccountController.java`:

```java
package com.musicboxd.api.accounts;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.musicboxd.api.security.OpenApiConfig;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

/** The caller's own account: MBD-17's protected endpoint, and what the SPA (MBD-22) calls after login. */
@RestController
public class AccountController {

	private final AccountService accounts;

	AccountController(AccountService accounts) {
		this.accounts = accounts;
	}

	@GetMapping("/api/v1/accounts/me")
	@SecurityRequirement(name = OpenApiConfig.BEARER)
	@ApiResponse(responseCode = "200", description = "The authenticated caller's account")
	@ApiResponse(responseCode = "401", description = "Missing, invalid or expired access token")
	public AccountView me(@AuthenticationPrincipal Jwt jwt) {
		return accounts.describe(UUID.fromString(jwt.getSubject()));
	}
}
```

Create `api/src/main/java/com/musicboxd/api/accounts/AccountsExceptionHandler.java`:

```java
package com.musicboxd.api.accounts;

import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders this module's errors (our ErrorResponseExceptions, bean-validation failures,
 * unreadable JSON) as RFC 9457 Problem Details instead of an empty error response.
 */
@RestControllerAdvice(basePackageClasses = AccountsExceptionHandler.class)
class AccountsExceptionHandler extends ResponseEntityExceptionHandler {
}
```

- [ ] **Step 6: Run the whole suite**

Run: `cd api && ./gradlew test`
Expected: BUILD SUCCESSFUL. New: `AuthFlowTest` 8, `SecurityRulesTest` 3. Existing `HealthControllerTest`, `DemoRateLimitControllerTest`, `DemoOpenApiTest` still pass (their routes are GETs, so public).

- [ ] **Step 7: Commit**

```bash
git add api/build.gradle.kts api/src/main/java/com/musicboxd/api/security api/src/main/java/com/musicboxd/api/accounts api/src/test/java/com/musicboxd/api/accounts/AuthFlowTest.java api/src/test/java/com/musicboxd/api/security/SecurityRulesTest.java api/src/test/java/com/musicboxd/api/health/HealthControllerTest.java
git commit -m "Add register, login and the token-protected /accounts/me endpoint (MBD-17)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Deploy wiring, runbook, and the manual high-risk check

**Files:**
- Modify: `deploy/docker-compose.yml`
- Modify: `deploy/docker-compose.prod.yml`
- Modify: `deploy/prod.env.example`
- Modify: `deploy/tests/test-compose-config.sh`
- Modify: `deploy/tests/test-backup-restore.sh`
- Create: `docs/runbooks/runbook-mbd-17-auth.md`

**Interfaces:**
- Consumes: the api's env contract from Tasks 1 and 3: `POSTGRES_HOST` (default `postgres`), `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `MUSICBOXD_JWT_SECRET` (required, Base64 ≥ 32 bytes).
- Produces: a prod stack where the api reads `/etc/musicboxd/postgres.env` and `/etc/musicboxd/api.env`, and starts only after Postgres is ready.

- [ ] **Step 1: Write the failing compose test**

In `deploy/tests/test-compose-config.sh`, after the line that writes `$TMP/postgres.env`, add:

```bash
printf 'MUSICBOXD_JWT_SECRET=prod-secret-from-api-env\n' > "$TMP/api.env"
```

and after the `! grep -q 'POSTGRES_PASSWORD: musicboxd'` line, add:

```bash
# MBD-17: the api reads its signing key and DB credentials from the host env files.
grep -q 'MUSICBOXD_JWT_SECRET: prod-secret-from-api-env' <<<"$OUT" || { echo "api lacks MUSICBOXD_JWT_SECRET from api.env"; exit 1; }
grep -q 'POSTGRES_PASSWORD: x' <<<"$OUT" || { echo "api lacks postgres.env credentials"; exit 1; }
! grep -q 'bG9jYWwtZGV2' <<<"$OUT"   # the local-dev JWT secret never reaches prod
grep -q 'condition: service_healthy' <<<"$OUT" || { echo "api does not wait for a healthy postgres"; exit 1; }
```

In `deploy/tests/test-backup-restore.sh`, after the line that writes `$TMP/postgres.env`, add (compose resolves every service's env files, even when only `postgres` starts):

```bash
printf 'MUSICBOXD_JWT_SECRET=x\n' > "$TMP/api.env"
```

- [ ] **Step 2: Run to verify it fails**

Run (bash, Docker running): `bash deploy/tests/test-compose-config.sh`
Expected: FAIL with `api lacks MUSICBOXD_JWT_SECRET from api.env`.

- [ ] **Step 3: Wire the prod compose file**

In `deploy/docker-compose.prod.yml`, in the `api` service replace the `env_file:` line with:

```yaml
    # postgres.env: DB name and credentials shared with postgres; api.env: MUSICBOXD_JWT_SECRET (MBD-17).
    env_file:
      - ${MUSICBOXD_ENV_DIR:-/etc/musicboxd}/postgres.env
      - ${MUSICBOXD_ENV_DIR:-/etc/musicboxd}/api.env
    depends_on:
      postgres:
        condition: service_healthy
```

In the `postgres` service, after `networks: [internal]` (before the `# No ports published (AD-11).` comment), add:

```yaml
    healthcheck:
      test: ['CMD-SHELL', 'pg_isready -U "$${POSTGRES_USER}" -d "$${POSTGRES_DB}"']
      interval: 5s
      timeout: 3s
      retries: 10
```

- [ ] **Step 4: Wire the local compose file**

In `deploy/docker-compose.yml`, in the `api` service, after `restart: on-failure`, add:

```yaml
    environment:
      POSTGRES_DB: musicboxd
      POSTGRES_USER: musicboxd
      POSTGRES_PASSWORD: musicboxd
      # Local-dev-only signing key (Base64 of a fixed 40-byte string); production reads
      # MUSICBOXD_JWT_SECRET from /etc/musicboxd/api.env (runbook-mbd-17-auth.md).
      MUSICBOXD_JWT_SECRET: bG9jYWwtZGV2LWp3dC1zZWNyZXQtbmV2ZXItdXNlLWluLXByb2QhIQ==
    depends_on:
      postgres:
        condition: service_healthy
```

In the `postgres` service, add the same `healthcheck:` block as in Step 3 after its `networks:` list (leave the existing comments as they are).

- [ ] **Step 5: Document the new env file**

In `deploy/prod.env.example`, change the first line to:

```
# deploy/prod.env.example  (copy to /etc/musicboxd/stack.env, postgres.env and api.env, chmod 600, owned by root)
```

and append:

```
# --- api.env ---
# JWT signing key (MBD-17): Base64 of >= 32 random bytes. Generate with: openssl rand -base64 32
# Rotating it logs everyone out (access tokens live ~15 minutes).
MUSICBOXD_JWT_SECRET=change-me-generated-with-openssl-rand-base64-32
```

- [ ] **Step 6: Run the deploy tests**

Run: `bash deploy/tests/test-compose-config.sh && bash deploy/tests/test-backup-restore.sh`
Expected: `prod compose config OK` and the backup/restore test's success output, exit 0.

- [ ] **Step 7: Write the runbook**

Create `docs/runbooks/runbook-mbd-17-auth.md`:

````markdown
# Runbook MBD-17 — Accounts: JWT secret and login smoke test

## Before merging MBD-17 (one time, on the EC2 host)

The api now refuses to start without `MUSICBOXD_JWT_SECRET`. If `/etc/musicboxd/api.env` is missing,
the deploy fails its health check and rolls back. Create it first (SSM session on the host):

```bash
sudo sh -c 'umask 077; printf "MUSICBOXD_JWT_SECRET=%s\n" "$(openssl rand -base64 32)" > /etc/musicboxd/api.env'
sudo chown root:root /etc/musicboxd/api.env
sudo ls -l /etc/musicboxd/api.env   # -rw------- root root
```

## After the deploy: smoke test (replace DOMAIN)

```bash
D=https://DOMAIN
curl -s -X POST $D/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"smoke+1@example.com","password":"correct-horse","username":"smoke_1"}'      # 201
curl -s -o /dev/null -w '%{http_code}\n' -X POST $D/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"smoke+1@example.com","password":"wrong-password"}'                          # 401
TOKEN=$(curl -s -X POST $D/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"smoke+1@example.com","password":"correct-horse"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -s $D/api/v1/accounts/me -H "Authorization: Bearer $TOKEN"                            # 200, username smoke_1
curl -s -o /dev/null -w '%{http_code}\n' $D/api/v1/accounts/me                              # 401
```

The smoke account stays in the database (account deletion is deferred, AD-12); use a fresh email each run.

## Rotating the secret

Replace the value in `/etc/musicboxd/api.env` and run `docker compose ... up -d api`. Every issued access
token stops working at once; people log in again (no refresh tokens until MBD-19).
````

- [ ] **Step 8: Manual high-risk check (a person does this before merge — ticket note)**

Run locally (Docker running), from the repo root:

```bash
docker compose -f deploy/docker-compose.yml up --build -d
D=http://localhost
curl -s -X POST $D/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"manual@example.com","password":"correct-horse","username":"manual_1"}'
curl -s -w '\n%{http_code}\n' -X POST $D/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"manual@example.com","password":"wrong-password"}'
TOKEN=$(curl -s -X POST $D/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"manual@example.com","password":"correct-horse"}' | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -s $D/api/v1/accounts/me -H "Authorization: Bearer $TOKEN"
docker compose -f deploy/docker-compose.yml exec postgres \
  psql -U musicboxd -d musicboxd -c 'SELECT username FROM profiles.profiles' -c '\d accounts.accounts'
docker compose -f deploy/docker-compose.yml down
```

Expected: register returns 201 JSON with `manual_1`; wrong password prints a Problem Details body then `401`; `/accounts/me` returns `manual_1`; `profiles.profiles` lists `manual_1`; `accounts.accounts` has no `username` column. Ask the user (João) to run or watch this and confirm before opening the PR; record the confirmation in the PR description.

- [ ] **Step 9: Commit**

```bash
git add deploy/docker-compose.yml deploy/docker-compose.prod.yml deploy/prod.env.example deploy/tests/test-compose-config.sh deploy/tests/test-backup-restore.sh docs/runbooks/runbook-mbd-17-auth.md
git commit -m "Wire the api to Postgres and its JWT secret in compose, add the MBD-17 runbook (MBD-17)

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

## Known gaps (raise with the user, do not silently implement)

- **AD-1 per-module Postgres roles and automated boundary verification** (Spring Modulith or ArchUnit) are not covered by any ticket in the epics. This plan uses one DB role and relies on package-private internals for the boundary. Suggest a separate ticket before a third module lands.
- **Spine stack row "Dependency locking"** (Gradle lockfiles) is not in place yet in `api/`; this plan adds dependencies without locking, the same as MBD-13 did.
- **Duplicate-email 409 reveals that an email is registered.** Accepted for the tracer bullet; MBD-18 (verification) and MBD-23 (rate limits on registration) are where to revisit it.
