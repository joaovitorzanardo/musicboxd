package com.musicboxd.api.accounts;

import static com.musicboxd.api.accounts.AccountServiceTest.uniqueEmail;
import static com.musicboxd.api.accounts.AccountServiceTest.uniqueUsername;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.SimpleMailMessage;

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.mail.MailTestConfiguration;
import com.musicboxd.api.mail.RecordingMailSender;
import com.musicboxd.api.profiles.UsernameTakenException;

/**
 * MBD-18 domain rules. NOT @Transactional on purpose: the email is sent after commit, so each
 * service call must commit for real.
 */
@SpringBootTest
@Import({ TestcontainersConfiguration.class, MailTestConfiguration.class })
class EmailVerificationServiceTest {

	private static final String PASSWORD = "correct-horse";

	@Autowired
	private AccountService accounts;

	@Autowired
	private EmailVerificationService verifications;

	@Autowired
	private RecordingMailSender mail;

	@Autowired
	private JdbcClient jdbc;

	@AfterEach
	void mailWorksAgain() {
		mail.failing(false);
	}

	@Test
	void registrationEmailsOneVerificationLinkToTheNormalizedAddress() {
		String email = uniqueEmail();
		accounts.register("  " + email.toUpperCase() + " ", PASSWORD, uniqueUsername());

		assertThat(mail.sentTo(email)).hasSize(1);
		SimpleMailMessage sent = mail.sentTo(email).getFirst();
		assertThat(sent.getFrom()).isEqualTo("no-reply@test.invalid");
		assertThat(sent.getSubject()).isEqualTo("Confirme seu email no Musicboxd");
		assertThat(sent.getText()).contains("24 horas");
		assertThat(mail.verificationLink(email)).startsWith("http://localhost/api/v1/auth/verify?token=");
	}

	@Test
	void unverifiedAccountIsRejectedEvenWithTheRightPassword() {
		String email = uniqueEmail();
		accounts.register(email, PASSWORD, uniqueUsername());

		assertThatThrownBy(() -> accounts.authenticate(email, PASSWORD))
			.isInstanceOf(EmailNotVerifiedException.class);
		assertThatThrownBy(() -> accounts.authenticate("  " + email.toUpperCase() + " ", PASSWORD))
			.isInstanceOf(EmailNotVerifiedException.class);
	}

	@Test
	void wrongPasswordOnAnUnverifiedAccountDoesNotRevealVerificationState() {
		String email = uniqueEmail();
		accounts.register(email, PASSWORD, uniqueUsername());
		assertThatThrownBy(() -> accounts.authenticate(email, "wrong-password"))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void visitingTheLinkLetsTheSameCredentialsAuthenticate() {
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());

		verifications.verify(tokenFor(email));

		assertThat(accounts.authenticate(email, PASSWORD)).isEqualTo(view.id());
	}

	@Test
	void onlyTheSha256OfTheTokenIsStored() throws Exception {
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());
		String raw = tokenFor(email);

		String stored = jdbc.sql("SELECT token_hash FROM accounts.email_verification_tokens WHERE account_id = :id")
			.param("id", view.id()).query(String.class).single();
		String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(UTF_8)));
		assertThat(stored).isEqualTo(expected).hasSize(64).isNotEqualTo(raw);
	}

	@Test
	void tokenExpiresAfterTwentyFourHours() {
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());
		String raw = tokenFor(email);

		OffsetDateTime expiresAt = jdbc.sql("SELECT expires_at FROM accounts.email_verification_tokens WHERE account_id = :id")
			.param("id", view.id()).query(OffsetDateTime.class).single();
		assertThat(Duration.between(OffsetDateTime.now(), expiresAt))
			.isBetween(Duration.ofHours(24).minusMinutes(1), Duration.ofHours(24));

		jdbc.sql("UPDATE accounts.email_verification_tokens SET expires_at = now() - interval '1 second' WHERE account_id = :id")
			.param("id", view.id()).update();
		assertThatThrownBy(() -> verifications.verify(raw)).isInstanceOf(InvalidVerificationTokenException.class);
		assertThatThrownBy(() -> accounts.authenticate(email, PASSWORD)).isInstanceOf(EmailNotVerifiedException.class);
	}

	@Test
	void unknownTokenIsRejected() {
		assertThatThrownBy(() -> verifications.verify("not-a-real-token"))
			.isInstanceOf(InvalidVerificationTokenException.class);
	}

	@Test
	void visitingTheLinkTwiceIsHarmless() {
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());
		String raw = tokenFor(email);

		verifications.verify(raw);
		verifications.verify(raw); // a mail scanner opened it first

		assertThat(accounts.authenticate(email, PASSWORD)).isEqualTo(view.id());
	}

	@Test
	void aTokenVerifiesOnlyItsOwnAccount() {
		String alice = uniqueEmail();
		String bob = uniqueEmail();
		accounts.register(alice, PASSWORD, uniqueUsername());
		accounts.register(bob, PASSWORD, uniqueUsername());

		verifications.verify(tokenFor(alice));

		assertThatThrownBy(() -> accounts.authenticate(bob, PASSWORD)).isInstanceOf(EmailNotVerifiedException.class);
	}

	@Test
	void resendReplacesTheOpenLink() {
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());
		String first = tokenFor(email);

		verifications.resend("  " + email.toUpperCase());

		assertThat(mail.sentTo(email)).hasSize(2);
		String second = tokenFor(email);
		assertThat(second).isNotEqualTo(first);
		assertThatThrownBy(() -> verifications.verify(first)).isInstanceOf(InvalidVerificationTokenException.class);
		verifications.verify(second);
		assertThat(accounts.authenticate(email, PASSWORD)).isEqualTo(view.id());
	}

	@Test
	void resendSendsNothingForUnknownOrAlreadyVerifiedEmails() {
		String unknown = uniqueEmail();
		verifications.resend(unknown);
		assertThat(mail.sentTo(unknown)).isEmpty();

		String email = uniqueEmail();
		accounts.register(email, PASSWORD, uniqueUsername());
		verifications.verify(tokenFor(email));
		verifications.resend(email);
		assertThat(mail.sentTo(email)).hasSize(1);
	}

	@Test
	void failedRegistrationSendsNoEmail() {
		String username = uniqueUsername();
		accounts.register(uniqueEmail(), PASSWORD, username);

		String email = uniqueEmail();
		assertThatThrownBy(() -> accounts.register(email, PASSWORD, username))
			.isInstanceOf(UsernameTakenException.class);
		assertThat(mail.sentTo(email)).isEmpty();
	}

	@Test
	void aMailOutageDoesNotUndoTheSignupAndResendRecovers() {
		mail.failing(true);
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());
		assertThat(mail.sentTo(email)).isEmpty();
		assertThat(accounts.describe(view.id()).email()).isEqualTo(email);

		mail.failing(false);
		verifications.resend(email);
		verifications.verify(tokenFor(email));
		assertThat(accounts.authenticate(email, PASSWORD)).isEqualTo(view.id());
	}

	@Test
	void linkHasNoDoubleSlashWhenTheBaseUrlEndsWithOne() {
		var props = new VerificationProperties(URI.create("https://musicboxd.com.br/"), "no-reply@musicboxd.com.br",
				Duration.ofHours(24));
		String email = uniqueEmail();

		new VerificationEmailListener(mail, props).send(new VerificationEmailRequested(UUID.randomUUID(), email, "abc_DEF-123"));

		assertThat(mail.verificationLink(email)).isEqualTo("https://musicboxd.com.br/api/v1/auth/verify?token=abc_DEF-123");
	}

	private String tokenFor(String email) {
		return RecordingMailSender.token(mail.verificationLink(email));
	}
}
