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
