package com.musicboxd.api.accounts;

import static com.musicboxd.api.accounts.AccountServiceTest.uniqueEmail;
import static com.musicboxd.api.accounts.AccountServiceTest.uniqueUsername;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.mail.MailTestConfiguration;
import com.musicboxd.api.mail.RecordingMailSender;

import jakarta.servlet.http.Cookie;

/** MBD-19 acceptance, over HTTP: login sets the cookie, refresh rotates it, replay is rejected. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, MailTestConfiguration.class })
class RefreshFlowTest {

	private static final String PASSWORD = "correct-horse";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private RecordingMailSender mail;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void loginSetsAHardenedRefreshCookie() throws Exception {
		login(verifiedAccount())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.accessToken").isString())
			.andExpect(jsonPath("$.refreshToken").doesNotExist())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
					containsString(RefreshCookies.NAME + "="),
					containsString("Path=/api/v1/auth/refresh"),
					containsString("Max-Age=2592000"),
					containsString("Secure"),
					containsString("HttpOnly"),
					containsString("SameSite=Lax"))));
	}

	@Test
	void refreshIssuesAWorkingAccessTokenAndRotatesTheCookie() throws Exception {
		String first = refreshCookie(login(verifiedAccount()).andReturn());

		MvcResult result = refresh(first)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andReturn();
		String accessToken = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
		assertThat(refreshCookie(result)).isNotEqualTo(first);

		mockMvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + accessToken))
			.andExpect(status().isOk());
	}

	@Test
	void replayAfterRotationIsRejectedRevokesTheSessionAndClearsTheCookie() throws Exception {
		String first = refreshCookie(login(verifiedAccount()).andReturn());
		String second = refreshCookie(refresh(first).andReturn());
		ageRotation(first, Duration.ofSeconds(11));

		refresh(first)
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value(InvalidRefreshTokenException.TYPE.toString()))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
		refresh(second).andExpect(status().isUnauthorized());
	}

	@Test
	void twoRefreshesWithinTheGraceWindowBothSucceed() throws Exception {
		String first = refreshCookie(login(verifiedAccount()).andReturn());

		String a = refreshCookie(refresh(first).andExpect(status().isOk()).andReturn());
		String b = refreshCookie(refresh(first).andExpect(status().isOk()).andReturn());

		refresh(a).andExpect(status().isOk());
		refresh(b).andExpect(status().isOk());
	}

	@Test
	void missingOrUnknownCookieIs401AndClearsIt() throws Exception {
		mockMvc.perform(post("/api/v1/auth/refresh"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.type").value(InvalidRefreshTokenException.TYPE.toString()))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
		refresh("").andExpect(status().isUnauthorized());
		refresh("not-a-real-token").andExpect(status().isUnauthorized());
		refresh("x".repeat(4_000)).andExpect(status().isUnauthorized());
	}

	@Test
	void aStaleBearerHeaderDoesNotBlockRefresh() throws Exception {
		String first = refreshCookie(login(verifiedAccount()).andReturn());

		mockMvc.perform(withCookie(post("/api/v1/auth/refresh"), first).header("Authorization", "Bearer not-a-jwt"))
			.andExpect(status().isOk());
	}

	private String verifiedAccount() throws Exception {
		String email = uniqueEmail();
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"%s","password":"%s","username":"%s"}""".formatted(email, PASSWORD, uniqueUsername())))
			.andExpect(status().isCreated());
		mockMvc.perform(get(URI.create(mail.verificationLink(email)))).andExpect(status().isOk());
		return email;
	}

	private ResultActions login(String email) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"%s","password":"%s"}""".formatted(email, PASSWORD)));
	}

	private ResultActions refresh(String cookieValue) throws Exception {
		return mockMvc.perform(withCookie(post("/api/v1/auth/refresh"), cookieValue));
	}

	private static MockHttpServletRequestBuilder withCookie(MockHttpServletRequestBuilder request, String value) {
		return request.cookie(new Cookie(RefreshCookies.NAME, value));
	}

	private static String refreshCookie(MvcResult result) {
		String prefix = RefreshCookies.NAME + "=";
		return result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
			.filter(h -> h.startsWith(prefix))
			.map(h -> h.substring(prefix.length(), h.indexOf(';')))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no " + RefreshCookies.NAME + " cookie set"));
	}

	private void ageRotation(String raw, Duration ago) {
		jdbc.sql("UPDATE accounts.refresh_tokens SET rotated_at = :at WHERE token_hash = :hash")
			.param("at", OffsetDateTime.now(ZoneOffset.UTC).minus(ago))
			.param("hash", OpaqueTokens.hash(raw)).update();
	}
}
