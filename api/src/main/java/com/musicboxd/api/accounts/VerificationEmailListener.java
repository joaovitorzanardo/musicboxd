package com.musicboxd.api.accounts;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
		catch (RuntimeException e) {
			// Not only MailException: the SES sender wraps just SesException, so credential and network
			// failures (SdkClientException) arrive unwrapped and must not escape the commit callback.
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
