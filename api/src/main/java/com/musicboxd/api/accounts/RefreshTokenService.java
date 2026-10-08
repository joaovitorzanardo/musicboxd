package com.musicboxd.api.accounts;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque refresh tokens (MBD-19, AD-8): hashed at rest, rotated on every use, reuse revokes the login's family.
 * Logout revokes it too (MBD-20).
 */
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
		UUID familyId = UUID.randomUUID();
		tokens.insertFamily(familyId, accountId);
		return insertSuccessor(familyId, accountId, clock.instant());
	}

	/**
	 * noRollbackFor: reuse detection revokes the family and then throws; a rollback would undo the revocation.
	 */
	@Transactional(noRollbackFor = InvalidRefreshTokenException.class)
	public Rotation rotate(String rawToken) {
		String hash = OpaqueTokens.hash(rawToken);
		var token = tokens.findForUpdate(hash).orElseThrow(InvalidRefreshTokenException::new);
		Instant now = clock.instant();
		if (tokens.lockFamilyAndCheckRevoked(token.familyId()) || !now.isBefore(token.expiresAt())) {
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

	/**
	 * Logout (MBD-20): revokes the token's whole family, so grace-window siblings and a successor that a
	 * parallel refresh is issuing right now die with it. Same lock order as {@link #rotate}. Unknown, expired
	 * and already-revoked tokens are a no-op: logout always succeeds and reveals nothing.
	 */
	@Transactional
	public void revoke(String rawToken) {
		tokens.findForUpdate(OpaqueTokens.hash(rawToken)).ifPresent(token -> {
			if (!tokens.lockFamilyAndCheckRevoked(token.familyId())) {
				tokens.revokeFamily(token.familyId(), clock.instant());
				log.info("Logout for account {}: revoked family {}", token.accountId(), token.familyId());
			}
		});
	}

	private String insertSuccessor(UUID familyId, UUID accountId, Instant now) {
		String raw = OpaqueTokens.generate();
		tokens.insert(OpaqueTokens.hash(raw), familyId, accountId, now.plus(props.ttl()));
		return raw;
	}
}
