package com.musicboxd.api.accounts;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RefreshTokenRepository {

	record StoredRefreshToken(UUID familyId, UUID accountId, Instant expiresAt, Instant rotatedAt) {
	}

	private final JdbcClient jdbc;

	RefreshTokenRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insertFamily(UUID familyId, UUID accountId) {
		jdbc.sql("INSERT INTO accounts.refresh_token_families (id, account_id) VALUES (:id, :account)")
			.param("id", familyId)
			.param("account", accountId)
			.update();
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
				SELECT family_id, account_id, expires_at, rotated_at FROM accounts.refresh_tokens
				WHERE token_hash = :hash FOR UPDATE""")
			.param("hash", tokenHash)
			.query((rs, n) -> new StoredRefreshToken(
					rs.getObject("family_id", UUID.class),
					rs.getObject("account_id", UUID.class),
					rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
					Optional.ofNullable(rs.getObject("rotated_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant).orElse(null)))
			.optional();
	}

	/**
	 * Locks the family for the rest of the transaction. Every rotation and every revocation takes this lock
	 * (always after the token's), so a revocation waits for an in-flight rotation and then covers its successor.
	 */
	boolean lockFamilyAndCheckRevoked(UUID familyId) {
		return jdbc.sql("SELECT revoked_at IS NOT NULL FROM accounts.refresh_token_families WHERE id = :id FOR UPDATE")
			.param("id", familyId)
			.query(Boolean.class)
			.single();
	}

	/** Keeps the first rotation time, so the grace window never slides. */
	void markRotated(String tokenHash, Instant at) {
		jdbc.sql("UPDATE accounts.refresh_tokens SET rotated_at = :at WHERE token_hash = :hash AND rotated_at IS NULL")
			.param("hash", tokenHash)
			.param("at", utc(at))
			.update();
	}

	void revokeFamily(UUID familyId, Instant at) {
		jdbc.sql("UPDATE accounts.refresh_token_families SET revoked_at = :at WHERE id = :id AND revoked_at IS NULL")
			.param("id", familyId)
			.param("at", utc(at))
			.update();
	}

	private static OffsetDateTime utc(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}
}
