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
