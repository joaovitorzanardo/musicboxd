package com.musicboxd.api.accounts;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One-time email verification links (MBD-18, AD-8). */
@Service
class EmailVerificationService {

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
		String raw = OpaqueTokens.generate();
		tokens.insert(OpaqueTokens.hash(raw), accountId, clock.instant().plus(props.ttl()));
		events.publishEvent(new VerificationEmailRequested(accountId, email, raw));
	}

	/** A consumed link verifies again without error: mail scanners often open links first. */
	@Transactional
	public void verify(String rawToken) {
		String hash = OpaqueTokens.hash(rawToken);
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
}
