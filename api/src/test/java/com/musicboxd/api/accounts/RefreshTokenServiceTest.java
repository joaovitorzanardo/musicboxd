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
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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

	@Autowired
	private PlatformTransactionManager transactions;

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

	@Test
	void revocationAlsoKillsASuccessorThatIsBeingIssuedConcurrently() throws Exception {
		String stolen = refreshTokens.issue(newAccount());
		String live = refreshTokens.rotate(stolen).refreshToken();
		ageRotation(stolen, Duration.ofSeconds(11));

		var rotated = new CountDownLatch(1);
		var commit = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			// The victim's tab rotates the live token; its transaction has not committed yet.
			Future<String> successor = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
				String next = refreshTokens.rotate(live).refreshToken();
				rotated.countDown();
				await(commit);
				return next;
			}));
			assertThat(rotated.await(10, SECONDS)).isTrue();
			// Meanwhile the stolen token is replayed after the grace window, which revokes the family.
			Future<?> replay = pool.submit(() -> assertThatThrownBy(() -> refreshTokens.rotate(stolen))
				.isInstanceOf(InvalidRefreshTokenException.class));
			Thread.sleep(500); // let the replay reach the database before the victim's tab commits
			commit.countDown();
			replay.get(10, SECONDS);

			String next = successor.get(10, SECONDS);
			assertThatThrownBy(() -> refreshTokens.rotate(next)).isInstanceOf(InvalidRefreshTokenException.class);
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

	private static void await(CountDownLatch latch) {
		try {
			assertThat(latch.await(10, SECONDS)).isTrue();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
