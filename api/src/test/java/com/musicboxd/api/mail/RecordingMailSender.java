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
	private volatile RuntimeException failure;

	@Override
	public void send(SimpleMailMessage message) {
		if (failure != null) {
			throw failure;
		}
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

	/** Simulates a non-mail failure such as a credential or network error; null clears it. */
	public void failingWith(RuntimeException failure) {
		this.failure = failure;
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
