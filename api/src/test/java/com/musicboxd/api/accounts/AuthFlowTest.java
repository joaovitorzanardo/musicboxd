package com.musicboxd.api.accounts;

import static com.musicboxd.api.accounts.AccountServiceTest.uniqueEmail;
import static com.musicboxd.api.accounts.AccountServiceTest.uniqueUsername;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;
import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.security.AuthProperties;

/** MBD-17 acceptance, over HTTP: register, log in, call the protected endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AuthFlowTest {

	private static final String PASSWORD = "correct-horse";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private JwtEncoder jwtEncoder;

	@Autowired
	private AuthProperties authProperties;

	@Test
	void newPersonRegistersLogsInAndCallsTheProtectedEndpoint() throws Exception {
		String email = uniqueEmail();
		String username = uniqueUsername();

		String registered = register(email, PASSWORD, username)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isString())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.username").value(username))
			.andExpect(jsonPath("$.password").doesNotExist())
			.andReturn().getResponse().getContentAsString();
		markVerified(registered);

		String body = login(email, PASSWORD)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.tokenType").value("Bearer"))
			.andExpect(jsonPath("$.expiresIn").value(900))
			.andReturn().getResponse().getContentAsString();
		String token = JsonPath.read(body, "$.accessToken");

		mockMvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + token))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.username").value(username));
	}

	@Test
	void emailWithSurroundingWhitespaceRegistersLowercasedOverHttp() throws Exception {
		String email = uniqueEmail();
		String registered = register("  " + email.toUpperCase() + " ", PASSWORD, uniqueUsername())
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.email").value(email))
			.andReturn().getResponse().getContentAsString();
		markVerified(registered);
		login(email, PASSWORD).andExpect(status().isOk());
	}

	@Test
	void usernameIsReadFromTheProfilesSchema() throws Exception {
		String username = uniqueUsername();
		String body = register(uniqueEmail(), PASSWORD, username)
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));

		String stored = jdbc.sql("SELECT username FROM profiles.profiles WHERE id = :id")
			.param("id", id).query(String.class).single();
		assertThat(stored).isEqualTo(username);
	}

	@Test
	void wrongPasswordAndUnknownEmailGetTheSame401() throws Exception {
		String email = uniqueEmail();
		register(email, PASSWORD, uniqueUsername()).andExpect(status().isCreated());

		String wrongPassword = login(email, "wrong-password")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.status").value(401))
			.andReturn().getResponse().getContentAsString();
		String unknownEmail = login(uniqueEmail(), PASSWORD)
			.andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();
		assertThat(unknownEmail).isEqualTo(wrongPassword);
	}

	@Test
	void protectedEndpointRejectsMissingMalformedAndExpiredTokens() throws Exception {
		mockMvc.perform(get("/api/v1/accounts/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().exists("WWW-Authenticate"))
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.title").value("Unauthorized"));
		mockMvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer not-a-jwt"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().exists("WWW-Authenticate"))
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(401));

		var expired = new TokenService(jwtEncoder, authProperties,
				Clock.fixed(Instant.now().minus(Duration.ofHours(1)), ZoneOffset.UTC))
			.issue(UUID.randomUUID());
		mockMvc.perform(get("/api/v1/accounts/me").header("Authorization", "Bearer " + expired.value()))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void registrationRejectsInvalidInputWithProblemDetails() throws Exception {
		register(uniqueEmail(), "short", uniqueUsername()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.status").value(400));
		register(uniqueEmail(), PASSWORD, "ab").andExpect(status().isBadRequest());
		register(uniqueEmail(), PASSWORD, "a".repeat(21)).andExpect(status().isBadRequest());
		register(uniqueEmail(), PASSWORD, "has space").andExpect(status().isBadRequest());
		register(uniqueEmail(), PASSWORD, "hyphen-ated").andExpect(status().isBadRequest());
		register("not-an-email", PASSWORD, uniqueUsername()).andExpect(status().isBadRequest());
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("{"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void boundaryLengthsAreAccepted() throws Exception {
		register(uniqueEmail(), "12345678", uniqueUsername()).andExpect(status().isCreated());
		String twenty = ("U_" + UUID.randomUUID().toString().replace("-", "")).substring(0, 20);
		register(uniqueEmail(), PASSWORD, twenty).andExpect(status().isCreated());
	}

	@Test
	void overlongPasswordIs400OnRegisterAnd401OnLoginNever500() throws Exception {
		String twoByteChars = "é".repeat(37); // 74 UTF-8 bytes
		register(uniqueEmail(), twoByteChars, uniqueUsername()).andExpect(status().isBadRequest());

		String email = uniqueEmail();
		register(email, PASSWORD, uniqueUsername()).andExpect(status().isCreated());
		login(email, twoByteChars).andExpect(status().isUnauthorized());
	}

	@Test
	void duplicateEmailOrUsernameIsAConflict() throws Exception {
		String email = uniqueEmail();
		String username = uniqueUsername();
		register(email, PASSWORD, username).andExpect(status().isCreated());

		register(email.toUpperCase(), PASSWORD, uniqueUsername())
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.status").value(409));
		register(uniqueEmail(), PASSWORD, username.toUpperCase()).andExpect(status().isConflict());
	}

	private void markVerified(String registerResponseBody) {
		AccountServiceTest.markVerified(jdbc, UUID.fromString(JsonPath.read(registerResponseBody, "$.id")));
	}

	private ResultActions register(String email, String password, String username) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"%s","password":"%s","username":"%s"}""".formatted(email, password, username)));
	}

	private ResultActions login(String email, String password) throws Exception {
		return mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("""
				{"email":"%s","password":"%s"}""".formatted(email, password)));
	}
}
