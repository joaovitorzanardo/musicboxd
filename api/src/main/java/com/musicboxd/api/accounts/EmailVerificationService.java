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
