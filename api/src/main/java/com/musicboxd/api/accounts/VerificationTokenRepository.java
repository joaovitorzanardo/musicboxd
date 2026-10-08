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
