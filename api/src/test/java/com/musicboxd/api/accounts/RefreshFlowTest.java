package com.musicboxd.api.accounts;

import static com.musicboxd.api.accounts.AccountServiceTest.uniqueEmail;
import static com.musicboxd.api.accounts.AccountServiceTest.uniqueUsername;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.mail.MailTestConfiguration;
import com.musicboxd.api.mail.RecordingMailSender;
import com.musicboxd.api.security.JwtConfig;

import jakarta.servlet.http.Cookie;

/** MBD-19/MBD-20 acceptance, over HTTP: login sets the cookie, refresh rotates it, replay is rejected, logout revokes it. */
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

	@Autowired
	private JwtDecoder jwtDecoder;

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

	@Test
	void logoutRevokesTheTokenServerSideNotJustTheCookie() throws Exception {
		String token = refreshCookie(login(verifiedAccount()).andReturn());

		logout(token)
			.andExpect(status().isNoContent())
			.andExpect(content().string(""))
			.andExpect(header().string(HttpHeaders.SET_COOKIE, allOf(
					containsString(RefreshCookies.NAME + "=;"),
					containsString("Max-Age=0"),
					containsString("Path=/api/v1/auth/refresh"))));

		// The client ignores the cleared cookie and replays the old value by hand: the server must refuse it.
		refresh(token)
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.type").value(InvalidRefreshTokenException.TYPE.toString()));
		Boolean revokedBeforeExpiry = jdbc.sql("""
				SELECT f.revoked_at IS NOT NULL AND t.expires_at > now() FROM accounts.refresh_tokens t
				JOIN accounts.refresh_token_families f ON f.id = t.family_id WHERE t.token_hash = :hash""")
			.param("hash", OpaqueTokens.hash(token)).query(Boolean.class).single();
		assertThat(revokedBeforeExpiry).isTrue();
	}

	@Test
	void aFreshLoginStillWorksAfterLogout() throws Exception {
		String email = verifiedAccount();
		logout(refreshCookie(login(email).andReturn())).andExpect(status().isNoContent());

		String fresh = refreshCookie(login(email).andExpect(status().isOk()).andReturn());

		refresh(fresh).andExpect(status().isOk());
	}

	@Test
	void accessTokensCarryTheRoleAndARoleChangeTakesEffectAtTheNextRefresh() throws Exception {
		String email = verifiedAccount();
		MvcResult first = login(email).andExpect(status().isOk()).andReturn();
		assertThat(roleClaim(first)).isEqualTo("USER");

		// AD-8: no API promotes anyone; Staff is assigned in the database (MBD-21 bootstrap or by hand).
		jdbc.sql("UPDATE accounts.accounts SET role = 'STAFF' WHERE lower(email) = lower(:email)")
			.param("email", email).update();

		MvcResult refreshed = refresh(refreshCookie(first)).andExpect(status().isOk()).andReturn();
		assertThat(roleClaim(refreshed)).isEqualTo("STAFF");
	}

	@Test
	void logoutNeedsNoAccessTokenAndIgnoresAStaleOne() throws Exception {
		String token = refreshCookie(login(verifiedAccount()).andReturn());

		mockMvc.perform(withCookie(delete("/api/v1/auth/refresh"), token).header("Authorization", "Bearer not-a-jwt"))
			.andExpect(status().isNoContent());
		refresh(token).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutWithoutAValidCookieIsStill204() throws Exception {
		mockMvc.perform(delete("/api/v1/auth/refresh"))
			.andExpect(status().isNoContent())
			.andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")));
		logout("").andExpect(status().isNoContent());
		logout("not-a-real-token").andExpect(status().isNoContent());
		logout("x".repeat(4_000)).andExpect(status().isNoContent());

		String token = refreshCookie(login(verifiedAccount()).andReturn());
		logout(token).andExpect(status().isNoContent());
		logout(token).andExpect(status().isNoContent()); // double click
	}

	private String verifiedAccount() throws Exception {
		String email = uniqueEmail();
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"%s","password":"%s","username":"%s"}""".formatted(email, PASSWORD, uniqueUsername())))
			.andExpect(status().isCreated());
		mockMvc.perform(get("/api/v1/auth/verify").queryParam("token", RecordingMailSender.token(mail.verificationLink(email))))
			.andExpect(status().isOk());
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

	private ResultActions logout(String cookieValue) throws Exception {
		return mockMvc.perform(withCookie(delete("/api/v1/auth/refresh"), cookieValue));
	}

	private static MockHttpServletRequestBuilder withCookie(MockHttpServletRequestBuilder request, String value) {
		return request.cookie(new Cookie(RefreshCookies.NAME, value));
	}

	private String roleClaim(MvcResult result) throws Exception {
		String token = JsonPath.read(result.getResponse().getContentAsString(), "$.accessToken");
		return jwtDecoder.decode(token).getClaimAsString(JwtConfig.ROLE_CLAIM);
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
