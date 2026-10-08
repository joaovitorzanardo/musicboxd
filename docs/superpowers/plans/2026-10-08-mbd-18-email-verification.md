# MBD-18 Email Verification Gates Login — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Registration creates a one-time verification token and emails its link through Amazon SES. Login rejects an unverified account (403) until the link is visited. After that, the same credentials log in.

**Architecture:** Sending is not hand-written. Spring Cloud AWS (`spring-cloud-aws-starter-ses`) auto-configures Spring's standard `MailSender` as `io.awspring.cloud.ses.SimpleEmailServiceMailSender`. It calls the SES `SendEmail` API with credentials from the EC2 instance role (no SMTP, no keys). Local dev and tests turn it off with `spring.cloud.aws.ses.enabled=false`; then our `MailConfig` supplies a logging `MailSender`. Production keeps the library default (`true`), so a forgotten setting can never silently downgrade production to logging. Inside `accounts`, an `EmailVerificationService` stores only the SHA-256 of a 32-byte random token in `accounts.email_verification_tokens`, and adds `accounts.accounts.email_verified_at`. It publishes a `VerificationEmailRequested` event inside the registration transaction. A `@TransactionalEventListener(AFTER_COMMIT)` builds a `SimpleMailMessage` and sends it, so a rolled-back signup sends nothing and an SES outage never undoes a signup. `AccountService.authenticate`, the only path that leads to a token, throws `EmailNotVerifiedException` (403) after the password matches. `GET /api/v1/auth/verify?token=` consumes the token. `POST /api/v1/auth/verification-email` resends (always 202, rate-limited per IP).

**Tech Stack:** Java 25, Spring Boot 4.1.1 (Spring Framework 7, Spring Security 7), Spring Cloud AWS 4.2.0 (`spring-cloud-aws-starter-ses`, BOM `io.awspring.cloud:spring-cloud-aws-dependencies:4.2.0`), Spring `MailSender`/`SimpleMailMessage`, Spring JDBC `JdbcClient`, Flyway per module schema, PostgreSQL 18, JUnit 5 / MockMvc / AssertJ, Testcontainers, Docker Compose v2, bash deploy tests.

**Spec:** Jira MBD-18 / `_bmad-output/initiative-musicboxd/epic-contas-acesso/story-email-verification-gates-login.md`. Parent epic: `_bmad-output/initiative-musicboxd/epic-contas-acesso/epic-contas-acesso.md`. Architecture: `_bmad-output/planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md` (AD-2, AD-8, AD-10, AD-11). UX copy: `_bmad-output/planning-artifacts/ux-designs/ux-teste-2026-09-26/EXPERIENCE.md` ("Verifique email" row, "Reenviar email").

**AWS console work:** `docs/runbooks/runbook-mbd-18-email-verification.md` (already written). Its sections 1–5 (SES domain identity with DKIM, sandbox recipients, IAM `musicboxd-ses`, IMDS hop limit 2, `api.env` lines) **must be done before this branch merges**. Otherwise the deploy fails its health check and rolls back.

**Branch:** if MBD-17 (`story/mbd-17-register-login`) is merged, `git switch main && git pull && git switch -c story/mbd-18-email-verification`. If it is not merged yet, branch from `story/mbd-17-register-login` instead. Commit messages end with `(MBD-18)` and the `Co-Authored-By` trailer. Run Gradle from `api/` (`./gradlew` in Git Bash, `.\gradlew.bat` in PowerShell). Docker must be running for Testcontainers.

## Global Constraints

- Email verification is required at signup; password recovery stays deferred (AD-8).
- Login for an unverified account answers **403** Problem Details with `type` `urn:musicboxd:problem:email-not-verified`, title `Email not verified`. The check runs **after** the password matches, so a wrong password on an unverified account is still the generic 401 (no verification-state leak).
- The raw token appears only in the email link. The database stores `SHA-256(raw)` as 64 lowercase hex characters. Tokens: 32 bytes from `SecureRandom`, Base64url without padding. TTL 24 h (`musicboxd.verification.ttl: 24h`). Single use.
- The verification link is `{musicboxd.verification.link-base-url}/api/v1/auth/verify?token={raw}`. The base URL comes from env `MUSICBOXD_PUBLIC_BASE_URL`; the sender comes from env `MUSICBOXD_MAIL_FROM`. Neither has a default: the api refuses to start without them (like `MUSICBOXD_JWT_SECRET`). Production: `https://musicboxd.com.br` and `no-reply@musicboxd.com.br`.
- Email goes through Spring's `MailSender` only; no code calls an AWS SDK client directly. Production uses Spring Cloud AWS's `SimpleEmailServiceMailSender` (SES `SendEmail` API, region `us-east-1` via `spring.cloud.aws.region.static`). `spring.cloud.aws.ses.enabled` is `false` only in the dev compose and tests, never in production config.
- Do **not** add `jakarta.mail` / `angus-mail` to the classpath: Spring Cloud AWS then swaps the `MailSender` bean for a `JavaMailSender` that needs `ses:SendRawEmail`, which the IAM policy does not grant.
- No static AWS keys anywhere (AD-11): credentials come from the default chain, which on the host resolves to the EC2 instance role over IMDSv2.
- Errors are RFC 9457 Problem Details. All routes are under `/api/v1` and documented by springdoc (AD-7).
- Schema changes only through versioned Flyway migrations in `db/migration/accounts`. The new table lives in the `accounts` schema; a foreign key inside the same schema is allowed.
- Email copy in Portuguese, matching the UX copy. API error messages stay in English, like MBD-17's.
- AD-10: the new AWS service's cost bound is stated in the runbook (USD 0.10 per 1,000 emails, sandbox cap 200/day). Resend is rate-limited (policy `verification-email`, 3 per 15 min per caller). Per-email and registration limits are MBD-23.
- Java sources use tabs; packages `com.musicboxd.api.<module>`. Repositories are package-private and use `JdbcClient`.
- Out of scope: SPA screens (MBD-22 builds "Verifique seu email" on top of these endpoints), SPF/MAIL FROM/DMARC and SES production access (MBD-24), refresh tokens (MBD-19), per-user limits on login/registration/email (MBD-23).

## Review Focus

- **The gate must hold for every spelling of the email.** `"  ALICE@Example.com "` on an unverified account is 403, not 200. (Task 2: `unverifiedAccountIsRejectedEvenWithTheRightPassword`.)
- **A rolled-back signup sends no email; an SES outage does not undo a signup.** A taken username must not mail a link to an account that does not exist. A failing sender must leave the account created and recoverable by resend. (Task 2: `failedRegistrationSendsNoEmail`, `aMailOutageDoesNotUndoTheSignupAndResendRecovers`.)
- **Mail scanners and double clicks.** Gmail/Outlook link scanners may GET the link before the person does. A second visit of a consumed link must answer 200 `verified`, not 400. (Task 2: `visitingTheLinkTwiceIsHarmless`; Task 3 HTTP acceptance clicks twice.)
- **Hostile token input.** A missing, empty, unknown or 10,000-character token must be 400 Problem Details, never a 500. (Task 3: `verifyRejectsMissingUnknownAndOverlongTokens`.)
- **Config typos in production.** An empty or relative `MUSICBOXD_PUBLIC_BASE_URL`, an empty `MUSICBOXD_MAIL_FROM`, or a trailing slash must not produce broken links. Empty/relative values fail startup; a trailing slash still yields exactly one `/` before `api`. (Task 2: `VerificationPropertiesTest`, `linkHasNoDoubleSlashWhenTheBaseUrlEndsWithOne`.)

---

## File Structure

**Mail wiring (Task 1)**: new package `api/src/main/java/com/musicboxd/api/mail/`
- `LoggingMailSender.java`: a `MailSender` that logs instead of sending (dev only).
- `MailConfig.java`: registers it when `spring.cloud.aws.ses.enabled=false`.
- Tests in `api/src/test/java/com/musicboxd/api/mail/`: `RecordingMailSender.java`, `MailTestConfiguration.java`, `MailConfigTest.java`.
- Modify `api/build.gradle.kts` (Spring Cloud AWS BOM + starter, export boot args), `application.yml` (region), test `config/application.yml`, `deploy/docker-compose.yml` (dev env).

**Verification domain (Task 2)**: in `api/src/main/java/com/musicboxd/api/accounts/`
- Create `VerificationProperties.java`, `VerificationTokenRepository.java`, `EmailVerificationService.java`, `VerificationEmailRequested.java`, `VerificationEmailListener.java`, `EmailNotVerifiedException.java`, `InvalidVerificationTokenException.java`.
- Modify `Account.java`, `AccountRepository.java`, `AccountService.java`, `AccountsConfig.java`.
- Create `api/src/main/resources/db/migration/accounts/V2__email_verification.sql`.
- Create `api/src/test/java/com/musicboxd/api/accounts/EmailVerificationServiceTest.java`, `VerificationPropertiesTest.java`. Modify `AccountServiceTest.java`, `AuthFlowTest.java`.

**HTTP (Task 3)**
- Modify `AuthController.java`, `security/SecurityConfig.java`, `application.yml` (rate-limit policy).
- Modify `AuthFlowTest.java`, `security/SecurityRulesTest.java`.

**Deploy (Task 4)**
- Modify `deploy/prod.env.example`, `deploy/tests/test-compose-config.sh`.
- Create `deploy/aws/host-ses-policy.json`.
- `docs/runbooks/runbook-mbd-18-email-verification.md` already exists; Task 4 only re-checks it against the final code.

---

### Task 1: Spring Cloud AWS SES starter, dev/test mail wiring

**Files:**
- Modify: `api/build.gradle.kts`, `api/src/main/resources/application.yml`, `api/src/test/resources/config/application.yml`, `deploy/docker-compose.yml`
- Create: `api/src/main/java/com/musicboxd/api/mail/LoggingMailSender.java`, `MailConfig.java`
- Create: `api/src/test/java/com/musicboxd/api/mail/RecordingMailSender.java`, `MailTestConfiguration.java`, `MailConfigTest.java`

**Interfaces:**
- Produces: a `org.springframework.mail.MailSender` bean in every context: `SimpleEmailServiceMailSender` by default, `LoggingMailSender` when `spring.cloud.aws.ses.enabled=false`. Test double `RecordingMailSender` with `sentTo(String) : List<SimpleMailMessage>`, `verificationLink(String) : String`, `static token(String link) : String`, `failing(boolean)`; `@TestConfiguration MailTestConfiguration` exposes it as a `@Primary MailSender`.

- [ ] **Step 1: Add the starter and prove it works with Boot 4.1.1 (compatibility gate)**

`api/build.gradle.kts`, `dependencies { ... }`:

```kotlin
	// Verification email through Amazon SES (MBD-18): Spring's MailSender over the SES API, credentials
	// from the EC2 instance role (AD-11). Do not add jakarta.mail: it swaps in a JavaMailSender that
	// needs ses:SendRawEmail.
	implementation(platform("io.awspring.cloud:spring-cloud-aws-dependencies:4.2.0"))
	implementation("io.awspring.cloud:spring-cloud-aws-starter-ses")
```

`api/src/main/resources/application.yml`, at the root (`spring:` already exists, so merge under it):

```yaml
spring:
  cloud:
    aws:
      # The SES identity lives in us-east-1 (runbook-mbd-18). Static, so startup never asks IMDS for a region.
      region:
        static: us-east-1
```

`api/src/test/resources/config/application.yml`, at the root:

```yaml
spring:
  cloud:
    aws:
      ses:
        # Tests never call AWS: MailConfig's LoggingMailSender, or MailTestConfiguration's recorder.
        enabled: false
```

Run: `./gradlew test`
Expected: the build resolves, the context starts, and all existing tests PASS.

**If this fails** (for example `NoSuchMethodError`/`ClassNotFoundException` in an `io.awspring` auto-configuration, or a Spring Cloud compatibility-verifier error naming Boot 4.1), Spring Cloud AWS 4.2.0 does not support Boot 4.1.1 yet. Try `4.1.1` the same way. If neither works, **stop and report back** to the human. The fallback is a ~30-line `MailSender` implemented on `software.amazon.awssdk:ses`'s `SesClient`. It is not part of this plan.

- [ ] **Step 2: Write the failing test**

`api/src/test/java/com/musicboxd/api/mail/MailConfigTest.java`:

```java
package com.musicboxd.api.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSender;

import com.musicboxd.api.TestcontainersConfiguration;

import io.awspring.cloud.ses.SimpleEmailServiceMailSender;

/** Exactly one MailSender in each mode; production (the default) gets the SES one. */
class MailConfigTest {

	@Nested
	@SpringBootTest(properties = "spring.cloud.aws.ses.enabled=true")
	@Import(TestcontainersConfiguration.class)
	class SesEnabledByDefaultInProduction {

		@Autowired
		private MailSender mailSender;

		@Test
		void mailGoesThroughSesWithoutNeedingCredentialsAtStartup() {
			assertThat(mailSender).isInstanceOf(SimpleEmailServiceMailSender.class);
		}
	}

	@Nested
	@SpringBootTest
	@Import(TestcontainersConfiguration.class)
	class SesDisabledInDevAndTests {

		@Autowired
		private MailSender mailSender;

		@Test
		void mailIsLoggedInstead() {
			assertThat(mailSender).isInstanceOf(LoggingMailSender.class);
		}
	}
}
```

The SES client builds without contacting AWS (static region, credentials resolved lazily on the first send), so the first context starts on a laptop with no AWS setup.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "com.musicboxd.api.mail.MailConfigTest*"`
Expected: compilation FAIL, `cannot find symbol: class LoggingMailSender`.

- [ ] **Step 4: Write the logging sender and its config**

`api/src/main/java/com/musicboxd/api/mail/LoggingMailSender.java`:

```java
package com.musicboxd.api.mail;

import java.util.Arrays;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

/**
 * Local dev only (spring.cloud.aws.ses.enabled=false): writes the email, link included, to the log instead
 * of sending it. Production keeps SES on, so live verification links never reach production logs.
 */
class LoggingMailSender implements MailSender {

	private static final Logger log = LoggerFactory.getLogger(LoggingMailSender.class);

	@Override
	public void send(SimpleMailMessage message) {
		log.info("Email not sent (SES disabled) to {}: {}\n{}", Arrays.toString(message.getTo()), message.getSubject(),
				message.getText());
	}

	@Override
	public void send(SimpleMailMessage... messages) {
		for (SimpleMailMessage message : messages) {
			send(message);
		}
	}
}
```

`api/src/main/java/com/musicboxd/api/mail/MailConfig.java`:

```java
package com.musicboxd.api.mail;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.MailSender;

/**
 * Spring Cloud AWS supplies the production MailSender (SES API, instance-role credentials). Its bean does
 * not back off for ours, so the dev logger exists only when SES auto-configuration is switched off.
 */
@Configuration
class MailConfig {

	@Bean
	@ConditionalOnProperty(name = "spring.cloud.aws.ses.enabled", havingValue = "false")
	MailSender loggingMailSender() {
		return new LoggingMailSender();
	}
}
```

`deploy/docker-compose.yml`, `api.environment`, after `MUSICBOXD_JWT_SECRET`:

```yaml
      # Local dev: verification emails are logged, not sent (docker compose logs api).
      SPRING_CLOUD_AWS_SES_ENABLED: "false"
      MUSICBOXD_MAIL_FROM: no-reply@localhost
      MUSICBOXD_PUBLIC_BASE_URL: http://localhost
```

(`MUSICBOXD_MAIL_FROM` and `MUSICBOXD_PUBLIC_BASE_URL` are read from Task 2 on. Adding them now keeps the dev env in one edit.)

`api/build.gradle.kts`, in `openApi { customBootRun { ... } }` replace the `args.set(...)` line:

```kotlin
		args.set(listOf(
			"--musicboxd.migrations.enabled=false",
			"--spring.cloud.aws.ses.enabled=false",
			"--musicboxd.verification.mail-from=export@localhost",
			"--musicboxd.verification.link-base-url=http://localhost"
		))
```

- [ ] **Step 5: Add the test double used by later tasks**

`api/src/test/java/com/musicboxd/api/mail/RecordingMailSender.java`:

```java
package com.musicboxd.api.mail;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;

/** Captures sent emails; {@link #failing(boolean)} simulates an SES outage. */
public class RecordingMailSender implements MailSender {

	private static final Pattern LINK = Pattern.compile("https?://\\S+/api/v1/auth/verify\\?token=[A-Za-z0-9_-]+");

	private final List<SimpleMailMessage> sent = new CopyOnWriteArrayList<>();
	private volatile boolean failing;

	@Override
	public void send(SimpleMailMessage message) {
		if (failing) {
			throw new MailSendException("simulated SES outage");
		}
		sent.add(new SimpleMailMessage(message));
	}

	@Override
	public void send(SimpleMailMessage... messages) {
		for (SimpleMailMessage message : messages) {
			send(message);
		}
	}

	public void failing(boolean failing) {
		this.failing = failing;
	}

	public List<SimpleMailMessage> sentTo(String to) {
		return sent.stream().filter(m -> m.getTo() != null && Arrays.asList(m.getTo()).contains(to)).toList();
	}

	/** The verification link in the most recent email to {@code to}. */
	public String verificationLink(String to) {
		List<SimpleMailMessage> emails = sentTo(to);
		if (emails.isEmpty()) {
			throw new AssertionError("no email sent to " + to);
		}
		Matcher m = LINK.matcher(emails.getLast().getText());
		if (!m.find()) {
			throw new AssertionError("no verification link in: " + emails.getLast().getText());
		}
		return m.group();
	}

	public static String token(String link) {
		return link.substring(link.indexOf("token=") + "token=".length());
	}
}
```

`api/src/test/java/com/musicboxd/api/mail/MailTestConfiguration.java`:

```java
package com.musicboxd.api.mail;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Import next to TestcontainersConfiguration to read the emails the api "sends". */
@TestConfiguration(proxyBeanMethods = false)
public class MailTestConfiguration {

	@Bean
	@Primary
	RecordingMailSender recordingMailSender() {
		return new RecordingMailSender();
	}
}
```

- [ ] **Step 6: Run the tests**

Run: `./gradlew test --tests "com.musicboxd.api.mail.MailConfigTest*"` → PASS (both nested classes).
Run: `./gradlew test` → all PASS.

- [ ] **Step 7: Commit**

```bash
git add api/build.gradle.kts api/src deploy/docker-compose.yml
git commit -m "Add Spring Cloud AWS SES as the MailSender, logged instead in dev and tests (MBD-18)"
```

---

### Task 2: Verification tokens, the login gate, email after commit

**Files:**
- Create: `api/src/main/resources/db/migration/accounts/V2__email_verification.sql`
- Create in `api/src/main/java/com/musicboxd/api/accounts/`: `VerificationProperties.java`, `VerificationTokenRepository.java`, `EmailVerificationService.java`, `VerificationEmailRequested.java`, `VerificationEmailListener.java`, `EmailNotVerifiedException.java`, `InvalidVerificationTokenException.java`
- Modify: `Account.java`, `AccountRepository.java`, `AccountService.java`, `AccountsConfig.java`, `api/src/main/resources/application.yml`, `api/src/test/resources/config/application.yml`
- Test: create `EmailVerificationServiceTest.java`, `VerificationPropertiesTest.java`; modify `AccountServiceTest.java`, `AuthFlowTest.java` (two existing login tests now need a verified account)

**Interfaces:**
- Consumes: `MailSender` bean, `RecordingMailSender`, `MailTestConfiguration` (Task 1); `Clock` bean (`security.JwtConfig`).
- Produces: `EmailVerificationService#issue(UUID accountId, String email)`, `#verify(String rawToken)`, `#resend(String email)`; `EmailNotVerifiedException` (403, `TYPE = urn:musicboxd:problem:email-not-verified`); `InvalidVerificationTokenException` (400); `record VerificationProperties(URI linkBaseUrl, String mailFrom, Duration ttl)`; `AccountService.normalize(String)` now package-private static; test helper `AccountServiceTest.markVerified(JdbcClient, UUID)`.

- [ ] **Step 1: Write the failing tests**

`api/src/test/java/com/musicboxd/api/accounts/VerificationPropertiesTest.java`:

```java
package com.musicboxd.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class VerificationPropertiesTest {

	private static final String FROM = "no-reply@musicboxd.com.br";
	private static final Duration DAY = Duration.ofHours(24);

	@Test
	void linkBaseUrlIsRequiredAndMustBeAbsoluteHttp() {
		for (String bad : new String[] { "", "musicboxd.com.br", "/relative", "ftp://musicboxd.com.br" }) {
			assertThatThrownBy(() -> new VerificationProperties(URI.create(bad), FROM, DAY))
				.as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("MUSICBOXD_PUBLIC_BASE_URL");
		}
		assertThatThrownBy(() -> new VerificationProperties(null, FROM, DAY))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void mailFromIsRequired() {
		URI base = URI.create("https://musicboxd.com.br");
		assertThatThrownBy(() -> new VerificationProperties(base, " ", DAY))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("MUSICBOXD_MAIL_FROM");
		assertThatThrownBy(() -> new VerificationProperties(base, null, DAY))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void validConfigBinds() {
		var props = new VerificationProperties(URI.create("https://musicboxd.com.br"), FROM, DAY);
		assertThat(props.ttl()).isEqualTo(DAY);
	}
}
```

`api/src/test/java/com/musicboxd/api/accounts/EmailVerificationServiceTest.java`:

```java
package com.musicboxd.api.accounts;

import static com.musicboxd.api.accounts.AccountServiceTest.uniqueEmail;
import static com.musicboxd.api.accounts.AccountServiceTest.uniqueUsername;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.mail.MailTestConfiguration;
import com.musicboxd.api.mail.RecordingMailSender;
import com.musicboxd.api.profiles.UsernameTakenException;

/**
 * MBD-18 domain rules. NOT @Transactional on purpose: the email is sent after commit, so each
 * service call must commit for real.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MailTestConfiguration.class })
class EmailVerificationServiceTest {

	private static final String PASSWORD = "correct-horse";

	@Autowired
	private AccountService accounts;

	@Autowired
	private EmailVerificationService verifications;

	@Autowired
	private RecordingMailSender mail;

	@Autowired
	private JdbcClient jdbc;

	@AfterEach
	void mailWorksAgain() {
		mail.failing(false);
	}

	@Test
	void registrationEmailsOneVerificationLinkToTheNormalizedAddress() {
		String email = uniqueEmail();
		accounts.register("  " + email.toUpperCase() + " ", PASSWORD, uniqueUsername());

		assertThat(mail.sentTo(email)).hasSize(1);
		SimpleMailMessage sent = mail.sentTo(email).getFirst();
		assertThat(sent.getFrom()).isEqualTo("no-reply@test.invalid");
		assertThat(sent.getSubject()).isEqualTo("Confirme seu email no Musicboxd");
		assertThat(sent.getText()).contains("24 horas");
		assertThat(mail.verificationLink(email)).startsWith("http://localhost/api/v1/auth/verify?token=");
	}

	@Test
	void unverifiedAccountIsRejectedEvenWithTheRightPassword() {
		String email = uniqueEmail();
		accounts.register(email, PASSWORD, uniqueUsername());

		assertThatThrownBy(() -> accounts.authenticate(email, PASSWORD))
			.isInstanceOf(EmailNotVerifiedException.class);
		assertThatThrownBy(() -> accounts.authenticate("  " + email.toUpperCase() + " ", PASSWORD))
			.isInstanceOf(EmailNotVerifiedException.class);
	}

	@Test
	void wrongPasswordOnAnUnverifiedAccountDoesNotRevealVerificationState() {
		String email = uniqueEmail();
		accounts.register(email, PASSWORD, uniqueUsername());
		assertThatThrownBy(() -> accounts.authenticate(email, "wrong-password"))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void visitingTheLinkLetsTheSameCredentialsAuthenticate() {
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());

		verifications.verify(tokenFor(email));

		assertThat(accounts.authenticate(email, PASSWORD)).isEqualTo(view.id());
	}

	@Test
	void onlyTheSha256OfTheTokenIsStored() throws Exception {
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());
		String raw = tokenFor(email);

		String stored = jdbc.sql("SELECT token_hash FROM accounts.email_verification_tokens WHERE account_id = :id")
			.param("id", view.id()).query(String.class).single();
		String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(UTF_8)));
		assertThat(stored).isEqualTo(expected).hasSize(64).isNotEqualTo(raw);
	}

	@Test
	void tokenExpiresAfterTwentyFourHours() {
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());
		String raw = tokenFor(email);

		OffsetDateTime expiresAt = jdbc.sql("SELECT expires_at FROM accounts.email_verification_tokens WHERE account_id = :id")
			.param("id", view.id()).query(OffsetDateTime.class).single();
		assertThat(Duration.between(OffsetDateTime.now(), expiresAt))
			.isBetween(Duration.ofHours(24).minusMinutes(1), Duration.ofHours(24));

		jdbc.sql("UPDATE accounts.email_verification_tokens SET expires_at = now() - interval '1 second' WHERE account_id = :id")
			.param("id", view.id()).update();
		assertThatThrownBy(() -> verifications.verify(raw)).isInstanceOf(InvalidVerificationTokenException.class);
		assertThatThrownBy(() -> accounts.authenticate(email, PASSWORD)).isInstanceOf(EmailNotVerifiedException.class);
	}

	@Test
	void unknownTokenIsRejected() {
		assertThatThrownBy(() -> verifications.verify("not-a-real-token"))
			.isInstanceOf(InvalidVerificationTokenException.class);
	}

	@Test
	void visitingTheLinkTwiceIsHarmless() {
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());
		String raw = tokenFor(email);

		verifications.verify(raw);
		verifications.verify(raw); // a mail scanner opened it first

		assertThat(accounts.authenticate(email, PASSWORD)).isEqualTo(view.id());
	}

	@Test
	void aTokenVerifiesOnlyItsOwnAccount() {
		String alice = uniqueEmail();
		String bob = uniqueEmail();
		accounts.register(alice, PASSWORD, uniqueUsername());
		accounts.register(bob, PASSWORD, uniqueUsername());

		verifications.verify(tokenFor(alice));

		assertThatThrownBy(() -> accounts.authenticate(bob, PASSWORD)).isInstanceOf(EmailNotVerifiedException.class);
	}

	@Test
	void resendReplacesTheOpenLink() {
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());
		String first = tokenFor(email);

		verifications.resend("  " + email.toUpperCase());

		assertThat(mail.sentTo(email)).hasSize(2);
		String second = tokenFor(email);
		assertThat(second).isNotEqualTo(first);
		assertThatThrownBy(() -> verifications.verify(first)).isInstanceOf(InvalidVerificationTokenException.class);
		verifications.verify(second);
		assertThat(accounts.authenticate(email, PASSWORD)).isEqualTo(view.id());
	}

	@Test
	void resendSendsNothingForUnknownOrAlreadyVerifiedEmails() {
		String unknown = uniqueEmail();
		verifications.resend(unknown);
		assertThat(mail.sentTo(unknown)).isEmpty();

		String email = uniqueEmail();
		accounts.register(email, PASSWORD, uniqueUsername());
		verifications.verify(tokenFor(email));
		verifications.resend(email);
		assertThat(mail.sentTo(email)).hasSize(1);
	}

	@Test
	void failedRegistrationSendsNoEmail() {
		String username = uniqueUsername();
		accounts.register(uniqueEmail(), PASSWORD, username);

		String email = uniqueEmail();
		assertThatThrownBy(() -> accounts.register(email, PASSWORD, username))
			.isInstanceOf(UsernameTakenException.class);
		assertThat(mail.sentTo(email)).isEmpty();
	}

	@Test
	void aMailOutageDoesNotUndoTheSignupAndResendRecovers() {
		mail.failing(true);
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());
		assertThat(mail.sentTo(email)).isEmpty();
		assertThat(accounts.describe(view.id()).email()).isEqualTo(email);

		mail.failing(false);
		verifications.resend(email);
		verifications.verify(tokenFor(email));
		assertThat(accounts.authenticate(email, PASSWORD)).isEqualTo(view.id());
	}

	@Test
	void linkHasNoDoubleSlashWhenTheBaseUrlEndsWithOne() {
		var props = new VerificationProperties(URI.create("https://musicboxd.com.br/"), "no-reply@musicboxd.com.br",
				Duration.ofHours(24));
		String email = uniqueEmail();

		new VerificationEmailListener(mail, props).send(new VerificationEmailRequested(UUID.randomUUID(), email, "abc_DEF-123"));

		assertThat(mail.verificationLink(email)).isEqualTo("https://musicboxd.com.br/api/v1/auth/verify?token=abc_DEF-123");
	}

	private String tokenFor(String email) {
		return RecordingMailSender.token(mail.verificationLink(email));
	}
}
```

In `AccountServiceTest.java`, add the helper and use it in the two tests that authenticate:

```java
	/** Accounts in tests that are not about verification skip the email round trip. */
	static void markVerified(JdbcClient jdbc, UUID accountId) {
		jdbc.sql("UPDATE accounts.accounts SET email_verified_at = now() WHERE id = :id").param("id", accountId).update();
	}
```

- `emailIsTrimmedAndLowercased`: after `AccountView view = accounts.register(...)`, add `markVerified(jdbc, view.id());`.
- `correctPasswordAuthenticates`: after `AccountView view = accounts.register(...)`, add `markVerified(jdbc, view.id());`.

In `AuthFlowTest.java` (the HTTP acceptance comes in Task 3; here only keep MBD-17's tests green), add a helper. `jdbc` is already autowired there:

```java
	private void markVerified(String registerResponseBody) {
		AccountServiceTest.markVerified(jdbc, UUID.fromString(JsonPath.read(registerResponseBody, "$.id")));
	}
```

- `newPersonRegistersLogsInAndCallsTheProtectedEndpoint`: capture the register body (`String registered = register(...)...andReturn().getResponse().getContentAsString();`) and call `markVerified(registered)` before `login`. Task 3 replaces this with the real link.
- `emailWithSurroundingWhitespaceRegistersLowercasedOverHttp`: same, before `login(email, PASSWORD).andExpect(status().isOk())`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.musicboxd.api.accounts.*"`
Expected: compilation FAIL (`EmailVerificationService`, `VerificationProperties`, `EmailNotVerifiedException` ... not found).

- [ ] **Step 3: Write the migration**

`api/src/main/resources/db/migration/accounts/V2__email_verification.sql`:

```sql
-- MBD-18: login requires a verified email (AD-8).
ALTER TABLE accounts.accounts ADD COLUMN email_verified_at timestamptz;

-- Accounts created before verification existed (MBD-17 smoke tests) are grandfathered in,
-- so this deploy locks nobody out.
UPDATE accounts.accounts SET email_verified_at = created_at;

-- One-time links. Only SHA-256(token) is stored; the raw token exists only in the email.
CREATE TABLE accounts.email_verification_tokens (
	token_hash  text        PRIMARY KEY,
	account_id  uuid        NOT NULL REFERENCES accounts.accounts (id),
	expires_at  timestamptz NOT NULL,
	consumed_at timestamptz,
	created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX email_verification_tokens_account_idx ON accounts.email_verification_tokens (account_id);
```

- [ ] **Step 4: Extend Account and its repository**

`Account.java`:

```java
package com.musicboxd.api.accounts;

import java.time.OffsetDateTime;
import java.util.UUID;

record Account(UUID id, String email, String passwordHash, OffsetDateTime emailVerifiedAt) {

	boolean emailVerified() {
		return emailVerifiedAt != null;
	}

	@Override
	public String toString() {
		return "Account[id=" + id + ", email=" + email + ", passwordHash=***, emailVerified=" + emailVerified() + "]";
	}
}
```

`AccountRepository.java`: both `SELECT`s become `SELECT id, email, password_hash, email_verified_at FROM accounts.accounts WHERE ...` (`insert` is unchanged: a new account starts unverified). Add:

```java
	/** No-op when already verified, so the first verification time is kept. */
	void markEmailVerified(UUID id, Instant at) {
		jdbc.sql("UPDATE accounts.accounts SET email_verified_at = :at WHERE id = :id AND email_verified_at IS NULL")
			.param("id", id)
			.param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
			.update();
	}
```

(imports `java.time.Instant`, `java.time.OffsetDateTime`, `java.time.ZoneOffset`; pgjdbc binds `OffsetDateTime`, not `Instant`.)

- [ ] **Step 5: Write properties, exceptions, event and token repository**

`VerificationProperties.java`:

```java
package com.musicboxd.api.accounts;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param linkBaseUrl where verification links point, e.g. {@code https://musicboxd.com.br}
 *        (env MUSICBOXD_PUBLIC_BASE_URL); no default, a wrong value sends people to a dead link
 * @param mailFrom sender, e.g. {@code no-reply@musicboxd.com.br} (env MUSICBOXD_MAIL_FROM); must be on
 *        the verified SES domain, and the host's IAM policy allows only this address
 * @param ttl how long a link stays valid
 */
@ConfigurationProperties("musicboxd.verification")
public record VerificationProperties(URI linkBaseUrl, String mailFrom, @DefaultValue("24h") Duration ttl) {

	public VerificationProperties {
		if (linkBaseUrl == null || !linkBaseUrl.isAbsolute() || !Set.of("http", "https").contains(linkBaseUrl.getScheme())
				|| linkBaseUrl.getHost() == null) {
			throw new IllegalStateException(
					"musicboxd.verification.link-base-url (env MUSICBOXD_PUBLIC_BASE_URL) must be set to an absolute http(s) URL");
		}
		if (mailFrom == null || mailFrom.isBlank()) {
			throw new IllegalStateException("musicboxd.verification.mail-from (env MUSICBOXD_MAIL_FROM) must be set");
		}
	}
}
```

`EmailNotVerifiedException.java`:

```java
package com.musicboxd.api.accounts;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Right password, unverified email (AD-8). The type lets the SPA (MBD-22) show "Verifique seu email". */
public class EmailNotVerifiedException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:email-not-verified");

	public EmailNotVerifiedException() {
		super(HttpStatus.FORBIDDEN, problem(), null);
	}

	private static ProblemDetail problem() {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
				"Confirm your email address with the link we sent before logging in");
		problem.setType(TYPE);
		problem.setTitle("Email not verified");
		return problem;
	}
}
```

`InvalidVerificationTokenException.java`:

```java
package com.musicboxd.api.accounts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Unknown, replaced or expired link: one message, so a link reveals nothing about accounts. */
public class InvalidVerificationTokenException extends ErrorResponseException {

	public InvalidVerificationTokenException() {
		super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"This verification link is invalid or expired; request a new one"), null);
	}
}
```

`VerificationEmailRequested.java`:

```java
package com.musicboxd.api.accounts;

import java.util.UUID;

/** Published inside the registration or resend transaction; the email goes out after it commits. */
record VerificationEmailRequested(UUID accountId, String email, String rawToken) {

	@Override
	public String toString() {
		return "VerificationEmailRequested[accountId=" + accountId + ", email=" + email + ", rawToken=***]";
	}
}
```

`VerificationTokenRepository.java`:

```java
package com.musicboxd.api.accounts;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class VerificationTokenRepository {

	record StoredToken(UUID accountId, Instant expiresAt, Instant consumedAt) {
	}

	private final JdbcClient jdbc;

	VerificationTokenRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(String tokenHash, UUID accountId, Instant expiresAt) {
		jdbc.sql("""
				INSERT INTO accounts.email_verification_tokens (token_hash, account_id, expires_at)
				VALUES (:hash, :account, :expires)""")
			.param("hash", tokenHash)
			.param("account", accountId)
			.param("expires", utc(expiresAt))
			.update();
	}

	/** Locks the row so two clicks of one link serialize. */
	Optional<StoredToken> findForUpdate(String tokenHash) {
		return jdbc.sql("""
				SELECT account_id, expires_at, consumed_at FROM accounts.email_verification_tokens
				WHERE token_hash = :hash FOR UPDATE""")
			.param("hash", tokenHash)
			.query((rs, n) -> new StoredToken(
					rs.getObject("account_id", UUID.class),
					rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
					Optional.ofNullable(rs.getObject("consumed_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null)))
			.optional();
	}

	void consume(String tokenHash, Instant at) {
		jdbc.sql("UPDATE accounts.email_verification_tokens SET consumed_at = :at WHERE token_hash = :hash AND consumed_at IS NULL")
			.param("hash", tokenHash)
			.param("at", utc(at))
			.update();
	}

	/** Resend replaces the open link: only the newest email works. */
	void deleteOpen(UUID accountId) {
		jdbc.sql("DELETE FROM accounts.email_verification_tokens WHERE account_id = :account AND consumed_at IS NULL")
			.param("account", accountId)
			.update();
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
```

- [ ] **Step 6: Write the service and the listener**

`EmailVerificationService.java`:

```java
package com.musicboxd.api.accounts;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One-time email verification links (MBD-18, AD-8). */
@Service
class EmailVerificationService {

	private static final int TOKEN_BYTES = 32;

	private final SecureRandom random = new SecureRandom();
	private final VerificationTokenRepository tokens;
	private final AccountRepository accounts;
	private final ApplicationEventPublisher events;
	private final VerificationProperties props;
	private final Clock clock;

	EmailVerificationService(VerificationTokenRepository tokens, AccountRepository accounts,
			ApplicationEventPublisher events, VerificationProperties props, Clock clock) {
		this.tokens = tokens;
		this.accounts = accounts;
		this.events = events;
		this.props = props;
		this.clock = clock;
	}

	/** Joins the caller's transaction; the email is sent only once it commits. */
	@Transactional
	public void issue(UUID accountId, String email) {
		byte[] bytes = new byte[TOKEN_BYTES];
		random.nextBytes(bytes);
		String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		tokens.insert(hash(raw), accountId, clock.instant().plus(props.ttl()));
		events.publishEvent(new VerificationEmailRequested(accountId, email, raw));
	}

	/** A consumed link verifies again without error: mail scanners often open links first. */
	@Transactional
	public void verify(String rawToken) {
		String hash = hash(rawToken);
		var token = tokens.findForUpdate(hash).orElseThrow(InvalidVerificationTokenException::new);
		if (token.consumedAt() != null) {
			return;
		}
		Instant now = clock.instant();
		if (!now.isBefore(token.expiresAt())) {
			throw new InvalidVerificationTokenException();
		}
		tokens.consume(hash, now);
		accounts.markEmailVerified(token.accountId(), now);
	}

	/** Silent for unknown and already-verified emails, so the endpoint does not reveal which accounts exist. */
	@Transactional
	public void resend(String email) {
		accounts.findByEmail(AccountService.normalize(email))
			.filter(account -> !account.emailVerified())
			.ifPresent(account -> {
				tokens.deleteOpen(account.id());
				issue(account.id(), account.email());
			});
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

`VerificationEmailListener.java`:

```java
package com.musicboxd.api.accounts;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Sends the link after the transaction commits. This is an external side effect, not cross-module
 * state, so AD-2's fail-closed, in-transaction rule does not apply: a rolled-back signup must not
 * email a dead link, and an SES outage must not undo a signup (the person can resend).
 */
@Component
class VerificationEmailListener {

	private static final Logger log = LoggerFactory.getLogger(VerificationEmailListener.class);
	static final String SUBJECT = "Confirme seu email no Musicboxd";

	private final MailSender mail;
	private final VerificationProperties props;

	VerificationEmailListener(MailSender mail, VerificationProperties props) {
		this.mail = mail;
		this.props = props;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	void send(VerificationEmailRequested event) {
		URI link = UriComponentsBuilder.fromUri(props.linkBaseUrl())
			.path("/api/v1/auth/verify")
			.queryParam("token", event.rawToken())
			.build()
			.toUri();
		var message = new SimpleMailMessage();
		message.setFrom(props.mailFrom());
		message.setTo(event.email());
		message.setSubject(SUBJECT);
		message.setText(body(link));
		try {
			mail.send(message);
		}
		catch (MailException e) {
			// Log the exception, never the message: its text holds a live link until it expires.
			log.error("Verification email for account {} failed; the person can request another", event.accountId(), e);
		}
	}

	private String body(URI link) {
		return """
				Olá!

				Clique no link abaixo para confirmar seu email e ativar sua conta no Musicboxd:

				%s

				O link vale por %d horas. Se você não criou uma conta no Musicboxd, ignore este email.
				""".formatted(link, props.ttl().toHours());
	}
}
```

`SimpleEmailServiceMailSender` wraps SES failures (`SesException`, credential errors) in a `MailSendException`, which is a `MailException`. If a non-`MailException` from the SDK escapes in practice (for example `SdkClientException` while loading credentials), the signup has still committed. AFTER_COMMIT listener exceptions are logged by Spring, not rethrown into the request. But the log line then lacks the account id, so widen the catch to `RuntimeException` if you see one.

`UriComponentsBuilder.path` collapses the double slash when the base ends in `/`. The `linkHasNoDoubleSlash...` test pins it. If it fails, strip the trailing slash in `VerificationProperties`' constructor.

- [ ] **Step 7: Gate authentication and issue at registration**

`AccountService.java`: inject `EmailVerificationService` and change three places:

```java
	private final EmailVerificationService verifications;

	AccountService(AccountRepository accounts, ProfilesApi profiles, EmailVerificationService verifications,
			PasswordEncoder passwordEncoder) {
		this.accounts = accounts;
		this.profiles = profiles;
		this.verifications = verifications;
		this.passwordEncoder = passwordEncoder;
		this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}
```

In `register`: `new Account(UUID.randomUUID(), normalize(email), passwordEncoder.encode(password), null)`, and after `profiles.createProfile(account.id(), username);` add:

```java
		verifications.issue(account.id(), account.email());
```

Update the Javadoc to `/** Creates the account, its profile and its verification link in one transaction: all or nothing. */`.

In `authenticate`, after the `InvalidCredentialsException` check and before `return`:

```java
		// After the password check, so only someone holding the password learns the email is unverified.
		// This is the only path to an access token (AuthController.login), so the gate covers every login.
		if (!account.get().emailVerified()) {
			throw new EmailNotVerifiedException();
		}
```

Change `private static String normalize` to `static String normalize` (package-private; resend uses the same rule).

`AccountsConfig.java`: add `@EnableConfigurationProperties(VerificationProperties.class)` on the class (import `org.springframework.boot.context.properties.EnableConfigurationProperties`).

`application.yml`, under `musicboxd:`:

```yaml
  verification:
    # No defaults: the api refuses to start without them (/etc/musicboxd/api.env, runbook-mbd-18).
    link-base-url: ${MUSICBOXD_PUBLIC_BASE_URL:}
    mail-from: ${MUSICBOXD_MAIL_FROM:}
    ttl: 24h
```

Test `config/application.yml`, under `musicboxd:`:

```yaml
  verification:
    link-base-url: http://localhost
    mail-from: no-reply@test.invalid
```

- [ ] **Step 8: Run tests to verify they pass**

Run: `./gradlew test --tests "com.musicboxd.api.accounts.*"` → PASS.
Run: `./gradlew test` → all PASS.

- [ ] **Step 9: Commit**

```bash
git add api/src
git commit -m "Gate login on a verified email and send a one-time link after signup commits (MBD-18)"
```

---

### Task 3: Verify and resend endpoints, HTTP acceptance

**Files:**
- Modify: `api/src/main/java/com/musicboxd/api/accounts/AuthController.java`, `api/src/main/java/com/musicboxd/api/security/SecurityConfig.java`, `api/src/main/resources/application.yml`
- Test: `api/src/test/java/com/musicboxd/api/accounts/AuthFlowTest.java`, `api/src/test/java/com/musicboxd/api/security/SecurityRulesTest.java`

**Interfaces:**
- Consumes: `EmailVerificationService#verify/resend`, `EmailNotVerifiedException.TYPE`, `RecordingMailSender`, `MailTestConfiguration`.
- Produces (HTTP contract MBD-22 builds on): `GET /api/v1/auth/verify?token=` → 200 `{"status":"verified"}` | 400 Problem; `POST /api/v1/auth/verification-email` `{"email"}` → 202 empty | 400 | 429; `POST /api/v1/auth/login` adds 403 with `type` `urn:musicboxd:problem:email-not-verified`.

- [ ] **Step 1: Write the failing tests**

`AuthFlowTest.java`: change the class annotation to `@Import({ TestcontainersConfiguration.class, MailTestConfiguration.class })`, add `@Autowired private RecordingMailSender mail;`, and replace the Task 2 `markVerified(...)` calls in the two MBD-17 tests with `verifyViaEmailedLink(email);` (then delete the `markVerified` helper). Add these tests and helpers:

```java
	@Test
	void unverifiedAccountCannotLogInUntilTheEmailedLinkIsVisited() throws Exception {
		String email = uniqueEmail();
		register(email, PASSWORD, uniqueUsername()).andExpect(status().isCreated());

		login(email, PASSWORD)
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value(EmailNotVerifiedException.TYPE.toString()))
			.andExpect(jsonPath("$.title").value("Email not verified"))
			.andExpect(jsonPath("$.accessToken").doesNotExist());

		verifyViaEmailedLink(email);
		verifyViaEmailedLink(email); // second visit: still 200

		login(email, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.accessToken").isString());
	}

	@Test
	void verifyRejectsMissingUnknownAndOverlongTokens() throws Exception {
		mockMvc.perform(get("/api/v1/auth/verify")).andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/auth/verify").queryParam("token", ""))
			.andExpect(status().isBadRequest());
		mockMvc.perform(get("/api/v1/auth/verify").queryParam("token", "unknown"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(400));
		mockMvc.perform(get("/api/v1/auth/verify").queryParam("token", "x".repeat(10_000)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void resendAnswers202ForAnyEmailAndIsLimitedPerIp() throws Exception {
		String email = uniqueEmail();
		register(email, PASSWORD, uniqueUsername()).andExpect(status().isCreated());

		String ip = "203.0.113.7";
		resend(email, ip).andExpect(status().isAccepted()).andExpect(content().string(""));
		resend(uniqueEmail(), ip).andExpect(status().isAccepted()).andExpect(content().string("")); // unknown: same answer
		assertThat(mail.sentTo(email)).hasSize(2);
		resend(email, ip).andExpect(status().isAccepted());
		resend(email, ip).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
		resend(email, "203.0.113.8").andExpect(status().isAccepted());

		resend("not-an-email-at-all".repeat(20), "203.0.113.9").andExpect(status().isBadRequest());
	}

	private void verifyViaEmailedLink(String email) throws Exception {
		mockMvc.perform(get(URI.create(mail.verificationLink(email))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("verified"));
	}

	private ResultActions resend(String email, String clientIp) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/verification-email")
			.with(request -> {
				request.setRemoteAddr(clientIp);
				return request;
			})
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"%s"}""".formatted(email)));
	}
```

(imports: `java.net.URI`, `com.musicboxd.api.mail.MailTestConfiguration`, `com.musicboxd.api.mail.RecordingMailSender`. The 380-character "email" is over `@Size(max = 254)`, hence 400.)

`SecurityRulesTest.contractListsAuthRoutesAndTheBearerScheme`: add

```java
			.andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['403']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/verify'].get.responses['200']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/verification-email'].post.responses['202']").exists())
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests com.musicboxd.api.accounts.AuthFlowTest --tests com.musicboxd.api.security.SecurityRulesTest`
Expected: FAIL. `/verify` is 404/401, resend is 401 (an unauthenticated write), and the contract paths are missing.

- [ ] **Step 3: Add the endpoints**

`AuthController.java`: add records, inject the service, add two handlers and a 403 doc on login:

```java
	public record ResendVerificationRequest(@NotBlank @Size(max = 254) String email) {

		public ResendVerificationRequest {
			email = email == null ? null : email.strip();
		}
	}

	public record VerificationResponse(String status) {
	}

	private final AccountService accounts;
	private final TokenService tokens;
	private final EmailVerificationService verifications;

	AuthController(AccountService accounts, TokenService tokens, EmailVerificationService verifications) {
		this.accounts = accounts;
		this.tokens = tokens;
		this.verifications = verifications;
	}
```

On `login` add `@ApiResponse(responseCode = "403", description = "Right password, email not verified yet (type urn:musicboxd:problem:email-not-verified)")`.

```java
	/** The emailed link points here. MBD-22 may move the link to an SPA page that calls this same endpoint. */
	@GetMapping("/verify")
	@ApiResponse(responseCode = "200", description = "Email verified (also when the link was already used)")
	@ApiResponse(responseCode = "400", description = "Missing, unknown, replaced or expired token")
	public VerificationResponse verify(@RequestParam @NotBlank @Size(max = 128) String token) {
		verifications.verify(token);
		return new VerificationResponse("verified");
	}

	@PostMapping("/verification-email")
	@ResponseStatus(HttpStatus.ACCEPTED)
	@RateLimited(policy = "verification-email")
	@ApiResponse(responseCode = "202", description = "If an unverified account has this email, a new link was sent")
	@ApiResponse(responseCode = "400", description = "Invalid email")
	@ApiResponse(responseCode = "429", description = "Too many requests from this caller; see Retry-After")
	public void resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
		verifications.resend(request.email());
	}
```

(imports: `org.springframework.web.bind.annotation.GetMapping`, `org.springframework.web.bind.annotation.RequestParam`, `com.musicboxd.api.ratelimit.RateLimited`.) The `@Size`/`@NotBlank` on `@RequestParam` use Spring 7's built-in controller method validation, which answers 400 Problem Details through `AccountsExceptionHandler`. If the overlong-token assertion gets 400 for the wrong reason, check that the body comes from validation, not from `InvalidVerificationTokenException`.

`application.yml`, under `musicboxd.rate-limit.policies`:

```yaml
      # MBD-18 resend: unauthenticated, so keyed per client IP. MBD-23 adds per-email limits.
      verification-email:
        capacity: 3
        refill-period: 15m
```

`SecurityConfig.java`: the permit lines become

```java
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
						"/api/v1/auth/verification-email").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/auth/verify").permitAll()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test` → all PASS.

- [ ] **Step 5: Check the contract export still boots without a database or AWS**

Run: `./gradlew generateOpenApiDocs`
Expected: BUILD SUCCESSFUL, and `build/openapi.json` contains `/api/v1/auth/verify` (`grep -c "auth/verify" build/openapi.json` ≥ 1). This is what `web/Dockerfile` runs. If it fails with a verification property error, recheck the `customBootRun` args from Task 1.

- [ ] **Step 6: Commit**

```bash
git add api/src
git commit -m "Add the verify and resend endpoints with a per-IP resend limit (MBD-18)"
```

---

### Task 4: Deploy wiring, IAM policy file, end-to-end check

**Files:**
- Create: `deploy/aws/host-ses-policy.json`
- Modify: `deploy/prod.env.example`, `deploy/tests/test-compose-config.sh`
- Check: `docs/runbooks/runbook-mbd-18-email-verification.md`

**Interfaces:**
- Consumes: env names `MUSICBOXD_MAIL_FROM`, `MUSICBOXD_PUBLIC_BASE_URL` (Task 2) and `SPRING_CLOUD_AWS_SES_ENABLED` (Task 1, dev only). `docker-compose.prod.yml` already passes all of `api.env` to the api, so it needs no change.

- [ ] **Step 1: Write the failing deploy test**

`deploy/tests/test-compose-config.sh`: replace the `api.env` printf line with

```bash
printf 'MUSICBOXD_JWT_SECRET=prod-secret-from-api-env\nMUSICBOXD_MAIL_FROM=no-reply@example.test\nMUSICBOXD_PUBLIC_BASE_URL=https://example.test\n' > "$TMP/api.env"
```

and after the `MUSICBOXD_JWT_SECRET` assertion add

```bash
# MBD-18: production sends through SES (library default); the dev compose's "log instead" switch must not leak in.
grep -q 'MUSICBOXD_MAIL_FROM: no-reply@example.test' <<<"$API" || { echo "api lacks MUSICBOXD_MAIL_FROM from api.env"; exit 1; }
grep -q 'MUSICBOXD_PUBLIC_BASE_URL: https://example.test' <<<"$API" || { echo "api lacks MUSICBOXD_PUBLIC_BASE_URL"; exit 1; }
if grep -q 'SPRING_CLOUD_AWS_SES_ENABLED' <<<"$OUT"; then echo "the prod stack overrides SES enablement"; exit 1; fi
if grep -qi 'AWS_SECRET_ACCESS_KEY\|AWS_ACCESS_KEY_ID' <<<"$OUT"; then echo "static AWS keys in the prod stack (AD-11)"; exit 1; fi
```

Run: `bash deploy/tests/test-compose-config.sh` → `prod compose config OK`. It already passes, because compose forwards every `api.env` key. The new lines guard against regressions. Prove they bite: temporarily add `SPRING_CLOUD_AWS_SES_ENABLED: "false"` under `api.environment` in `docker-compose.prod.yml`, run → FAIL "the prod stack overrides SES enablement", then revert.

- [ ] **Step 2: Commit the IAM policy and the env example**

`deploy/aws/host-ses-policy.json` (exactly what runbook section 3 pastes; `SimpleEmailServiceMailSender` calls the SES `SendEmail` API):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "SendAsNoReplyOnly",
      "Effect": "Allow",
      "Action": "ses:SendEmail",
      "Resource": "arn:aws:ses:us-east-1:<ACCOUNT_ID>:identity/*",
      "Condition": {
        "StringEquals": { "ses:FromAddress": "no-reply@musicboxd.com.br" }
      }
    }
  ]
}
```

`deploy/prod.env.example`, under `# --- api.env ---`, append:

```bash
# MBD-18 verification email (runbook-mbd-18-email-verification.md). No defaults: the api refuses to start without them.
# SES itself needs no setting here: it is on by default and uses the instance role.
MUSICBOXD_MAIL_FROM=no-reply@musicboxd.example.test
# Start of every verification link; no trailing slash.
MUSICBOXD_PUBLIC_BASE_URL=https://musicboxd.example.test
```

- [ ] **Step 3: Local end-to-end with the dev compose**

```bash
cd deploy && docker compose up -d --build
curl -s -w '\n%{http_code}\n' -X POST http://localhost/api/v1/auth/register -H 'Content-Type: application/json' \
  -d '{"email":"local+1@example.com","password":"correct-horse","username":"local_1"}'        # 201
curl -s -w '\n%{http_code}\n' -X POST http://localhost/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"local+1@example.com","password":"correct-horse"}'                              # 403
LINK=$(docker compose logs api | grep -o 'http://localhost/api/v1/auth/verify?token=[A-Za-z0-9_-]*' | tail -1)
curl -s "$LINK"                                                                                 # {"status":"verified"}
curl -s -w '\n%{http_code}\n' -X POST http://localhost/api/v1/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"local+1@example.com","password":"correct-horse"}'                              # 200
docker compose down
```

Use a fresh email if the local volume already has `local+1`.

- [ ] **Step 4: Re-read the runbook against the final code**

Open `docs/runbooks/runbook-mbd-18-email-verification.md` and confirm each name it uses matches the code: the two `api.env` lines (`MUSICBOXD_MAIL_FROM`, `MUSICBOXD_PUBLIC_BASE_URL`), `/api/v1/auth/verify`, `/api/v1/auth/verification-email`, `{"status":"verified"}`, subject `Confirme seu email no Musicboxd`, the 403 title `Email not verified`, the log line `Verification email for account`, the startup errors (`...link-base-url (env MUSICBOXD_PUBLIC_BASE_URL) must be set`, `...mail-from (env MUSICBOXD_MAIL_FROM) must be set`), table `accounts.email_verification_tokens`, policy `verification-email` 3/15 min, and the `grep -rn "tokens.issue(" api/src/main/java` person check. Fix any drift in the runbook.

- [ ] **Step 5: Full verification and commit**

```bash
cd api && ./gradlew test && cd .. && for t in deploy/tests/test-*.sh; do bash "$t" || echo "FAILED: $t"; done
git add deploy docs/runbooks/runbook-mbd-18-email-verification.md
git commit -m "Wire the SES settings into the prod env, add the host SES policy and the MBD-18 runbook (MBD-18)"
```

All Gradle tests pass. Deploy tests that need Docker report OK.

- [ ] **Step 6: Hand-off**

Before opening the PR, ask the human to complete runbook sections 1–5 (AWS console + `api.env`). After merge and deploy, they run sections 7–8 and fill in the verification table. Section 8 is the story's high-risk person check.

---

## Known gaps (tell the human; not in this plan)

- **Sandbox:** until MBD-24 (production access, SPF/MAIL FROM, DMARC), production can only send verification email to addresses verified in SES. Real sign-ups cannot complete. This is the epic's accepted open question.
- **Link lands on raw JSON:** `{"status":"verified"}` in the browser. MBD-22 (SPA auth screens) should add a page; it can keep this endpoint or point the link at an SPA route that calls it.
- **Rate limits:** resend is limited per IP only. Registration (which also sends an email) and per-email limits are MBD-23.
- **MBD-19 (refresh tokens)** must not issue tokens to unverified accounts. That holds as long as refresh tokens are only minted at login after `authenticate`. Note it on MBD-19.
- **Bounce/complaint handling** (SNS) is needed before MBD-24 asks AWS for production access.
- **Spring Cloud AWS and Boot 4.1:** 4.2.0 is built on Spring Cloud 5.0 (Boot 4.0). Task 1 Step 1 is the gate. Re-check the pin when upgrading Boot.
