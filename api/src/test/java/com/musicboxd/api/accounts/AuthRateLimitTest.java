package com.musicboxd.api.accounts;

import static com.musicboxd.api.accounts.AccountServiceTest.markVerified;
import static com.musicboxd.api.accounts.AccountServiceTest.uniqueEmail;
import static com.musicboxd.api.accounts.AccountServiceTest.uniqueUsername;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.mail.MailTestConfiguration;
import com.musicboxd.api.mail.RecordingMailSender;

/**
 * MBD-23 acceptance: login, registration and verification email are each throttled per email
 * (one account) and per client IP. The policies are overridden here so the test owns the numbers;
 * every request picks its own IP unless a test is about the per-IP limit.
 */
@SpringBootTest(properties = {
	"musicboxd.rate-limit.policies.login-per-ip.capacity=5",
	"musicboxd.rate-limit.policies.login-per-ip.refill-period=1h",
	"musicboxd.rate-limit.policies.login-per-email.capacity=3",
	"musicboxd.rate-limit.policies.login-per-email.refill-period=1h",
	"musicboxd.rate-limit.policies.register-per-ip.capacity=3",
	"musicboxd.rate-limit.policies.register-per-ip.refill-period=1h",
	"musicboxd.rate-limit.policies.register-per-email.capacity=2",
	"musicboxd.rate-limit.policies.register-per-email.refill-period=1h",
	"musicboxd.rate-limit.policies.verification-email-per-ip.capacity=10",
	"musicboxd.rate-limit.policies.verification-email-per-ip.refill-period=1h",
	"musicboxd.rate-limit.policies.verification-email-per-email.capacity=2",
	"musicboxd.rate-limit.policies.verification-email-per-email.refill-period=1h" })
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfiguration.class })
class AuthRateLimitTest {

	private static final String PASSWORD = "correct-horse";
	private static final AtomicInteger IPS = new AtomicInteger();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private RecordingMailSender mail;

	// --- login ---

	@Test
	void loginIsThrottledPerEmailWhicheverIpTheAttemptsComeFrom() throws Exception {
		String email = uniqueEmail();
		register(email, uniqueUsername(), freshIp()).andExpect(status().isCreated());

		for (int i = 0; i < 3; i++) {
			login(email, "wrong-password", freshIp()).andExpect(status().isUnauthorized());
		}
		login(email, "wrong-password", freshIp())
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.status").value(429));
		// Another account is not affected.
		login(uniqueEmail(), "wrong-password", freshIp()).andExpect(status().isUnauthorized());
	}

	@Test
	void aThrottledEmailIsRefusedEvenWithTheRightPassword() throws Exception {
		String email = registerVerified();

		// Case and padding variants count against the same account.
		login("  " + email.toUpperCase() + " ", "wrong-password", freshIp()).andExpect(status().isUnauthorized());
		login(email.toUpperCase(), "wrong-password", freshIp()).andExpect(status().isUnauthorized());
		login(email, "wrong-password", freshIp()).andExpect(status().isUnauthorized());

		login(email, PASSWORD, freshIp()).andExpect(status().isTooManyRequests());
	}

	@Test
	void unknownEmailsAreThrottledLikeKnownOnes() throws Exception {
		String nobody = uniqueEmail();
		for (int i = 0; i < 3; i++) {
			login(nobody, "wrong-password", freshIp()).andExpect(status().isUnauthorized());
		}
		login(nobody, "wrong-password", freshIp()).andExpect(status().isTooManyRequests());
	}

	@Test
	void loginIsThrottledPerIpAcrossEmails() throws Exception {
		String ip = freshIp();
		for (int i = 0; i < 5; i++) {
			login(uniqueEmail(), "wrong-password", ip).andExpect(status().isUnauthorized());
		}
		login(uniqueEmail(), "wrong-password", ip).andExpect(status().isTooManyRequests());
		login(uniqueEmail(), "wrong-password", freshIp()).andExpect(status().isUnauthorized());
	}

	@Test
	void oversizedAndBlankEmailsAreAnsweredNotCrashed() throws Exception {
		login("a".repeat(10_000) + "@example.com", "wrong-password", freshIp()).andExpect(status().isUnauthorized());
		login("   ", "wrong-password", freshIp()).andExpect(status().isBadRequest());
	}

	@Test
	void openApiDocumentsTheLoginThrottle() throws Exception {
		mockMvc.perform(get("/api/v1/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['429']").exists());
	}

	// --- registration ---

	@Test
	void registrationIsThrottledPerEmail() throws Exception {
		String email = uniqueEmail();
		register(email, uniqueUsername(), freshIp()).andExpect(status().isCreated());
		register(email, uniqueUsername(), freshIp()).andExpect(status().isConflict());

		register(email, uniqueUsername(), freshIp())
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"));
	}

	@Test
	void registrationIsThrottledPerIpAndAThrottledOneCreatesNothing() throws Exception {
		String ip = freshIp();
		for (int i = 0; i < 3; i++) {
			register(uniqueEmail(), uniqueUsername(), ip).andExpect(status().isCreated());
		}

		String fourth = uniqueEmail();
		register(fourth, uniqueUsername(), ip).andExpect(status().isTooManyRequests());

		assertThat(mail.sentTo(fourth)).isEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM accounts.accounts WHERE email = :email")
			.param("email", fourth)
			.query(Long.class)
			.single()).isZero();
		register(uniqueEmail(), uniqueUsername(), freshIp()).andExpect(status().isCreated());
	}

	@Test
	void openApiDocumentsTheRegistrationThrottle() throws Exception {
		mockMvc.perform(get("/api/v1/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/register'].post.responses['429']").exists());
	}

	// --- verification email ---

	@Test
	void verificationEmailIsThrottledPerEmailWhicheverIp() throws Exception {
		String email = uniqueEmail();
		register(email, uniqueUsername(), freshIp()).andExpect(status().isCreated()); // first email

		resend(email, freshIp()).andExpect(status().isAccepted());
		resend(email, freshIp()).andExpect(status().isAccepted());
		resend(email, freshIp())
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"));

		assertThat(mail.sentTo(email)).hasSize(3);
	}

	@Test
	void verificationEmailThrottleDoesNotRevealWhetherTheAccountExists() throws Exception {
		String nobody = uniqueEmail();
		resend(nobody, freshIp()).andExpect(status().isAccepted());
		resend(nobody, freshIp()).andExpect(status().isAccepted());
		resend(nobody, freshIp()).andExpect(status().isTooManyRequests());
	}

	@Test
	void verificationEmailIsStillThrottledPerIp() throws Exception {
		String ip = freshIp();
		for (int i = 0; i < 10; i++) {
			resend(uniqueEmail(), ip).andExpect(status().isAccepted());
		}
		resend(uniqueEmail(), ip).andExpect(status().isTooManyRequests());
	}

	// --- helpers ---

	/** A client IP no other request in this class has used, so only the bucket under test fills up. */
	private static String freshIp() {
		int n = IPS.incrementAndGet();
		return "10.23." + (n / 250) + "." + (n % 250 + 1);
	}

	private String registerVerified() throws Exception {
		String email = uniqueEmail();
		register(email, uniqueUsername(), freshIp()).andExpect(status().isCreated());
		UUID id = jdbc.sql("SELECT id FROM accounts.accounts WHERE email = :email")
			.param("email", email)
			.query(UUID.class)
			.single();
		markVerified(jdbc, id);
		return email;
	}

	private ResultActions login(String email, String password, String clientIp) throws Exception {
		return send("/api/v1/auth/login", clientIp, """
				{"email":"%s","password":"%s"}""".formatted(email, password));
	}

	private ResultActions register(String email, String username, String clientIp) throws Exception {
		return send("/api/v1/auth/register", clientIp, """
				{"email":"%s","password":"%s","username":"%s"}""".formatted(email, PASSWORD, username));
	}

	private ResultActions resend(String email, String clientIp) throws Exception {
		return send("/api/v1/auth/verification-email", clientIp, """
				{"email":"%s"}""".formatted(email));
	}

	private ResultActions send(String path, String clientIp, String json) throws Exception {
		return mockMvc.perform(post(path)
			.with(request -> {
				request.setRemoteAddr(clientIp);
				return request;
			})
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}
}
