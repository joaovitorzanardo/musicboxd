package com.musicboxd.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

/** MBD-21 acceptance: /api/v1/admin/** is Staff-only for every method; other writes need USER or STAFF. */
@SpringBootTest
@AutoConfigureMockMvc
@Import({ TestcontainersConfiguration.class, AdminRouteProtectionTest.AdminProbe.class })
class AdminRouteProtectionTest {

	/**
	 * No admin route exists until MBD-28, so this stands in for one. Nested in a test class, component
	 * scanning skips it; only this test's context imports it, so the published contract never lists it.
	 */
	@RestController
	@RequestMapping({ "/api/v1/admin/probe", "/api/v1/admin/probe/**" })
	static class AdminProbe {

		@RequestMapping
		Map<String, Boolean> any() {
			return Map.of("ok", true);
		}
	}

	private static final HttpMethod[] METHODS = { HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT,
			HttpMethod.PATCH, HttpMethod.DELETE };

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JwtEncoder jwtEncoder;

	/** Every method on routes that exist and routes that don't: a sample, not just one (story's risk note). */
	static Stream<Arguments> adminRequests() {
		var paths = new String[] { "/api/v1/admin/probe", "/api/v1/admin/probe/albums/42",
				"/api/v1/admin/catalog/albums", "/api/v1/admin/reviews/7", "/api/v1/admin", "/api/v1/admin/" };
		return Stream.of(paths).flatMap(path -> Stream.of(METHODS).map(method -> Arguments.of(method, path)));
	}

	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("adminRequests")
	void userTokenIsForbiddenOnEveryAdminRoute(HttpMethod method, String path) throws Exception {
		mockMvc.perform(request(method, path).header("Authorization", bearer(Role.USER)))
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(403))
			.andExpect(jsonPath("$.title").value("Forbidden"));
	}

	@ParameterizedTest(name = "{0} {1}")
	@MethodSource("adminRequests")
	void adminRoutesRejectAnonymousCallers(HttpMethod method, String path) throws Exception {
		mockMvc.perform(request(method, path)).andExpect(status().isUnauthorized());
	}

	@Test
	void staffTokenReachesAdminRoutesWithEveryMethod() throws Exception {
		for (HttpMethod method : METHODS) {
			mockMvc.perform(request(method, "/api/v1/admin/probe/albums/42").header("Authorization", bearer(Role.STAFF)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(true));
		}
		// Past security, an unknown admin route is an honest 404.
		mockMvc.perform(get("/api/v1/admin/catalog/albums").header("Authorization", bearer(Role.STAFF)))
			.andExpect(status().isNotFound());
	}

	@Test
	void tokensWithoutAKnownRoleAreForbiddenOnAdmin() throws Exception {
		// Issued before MBD-21 (no claim), or carrying a value we never issue.
		for (String token : new String[] { tokenWithRole(null), tokenWithRole("ADMIN"), tokenWithRole("staff") }) {
			mockMvc.perform(get("/api/v1/admin/probe").header("Authorization", "Bearer " + token))
				.andExpect(status().isForbidden());
			mockMvc.perform(post("/api/v1/health").header("Authorization", "Bearer " + token))
				.andExpect(status().isForbidden());
		}
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/v1//admin/probe", "/api/v1/./admin/probe", "/api/v1/x/../admin/probe",
			"/api/v1/admin;x=1/probe", "/api/v1/admin/probe/", "/api/v1/admin/probe;jsessionid=1" })
	void pathTricksNeverReachAnAdminRouteAsUser(String path) throws Exception {
		int status = mockMvc.perform(get(path).header("Authorization", bearer(Role.USER)))
			.andReturn().getResponse().getStatus();
		// The firewall rejects the odd ones (400); the rest hit the admin rule (403). Never 200, never 404.
		assertThat(status).isIn(400, 403);
	}

	@Test
	void writesOutsideAdminNeedAUserOrStaffToken() throws Exception {
		mockMvc.perform(post("/api/v1/health")).andExpect(status().isUnauthorized());
		// Past security; no POST handler on /health, so the request is answered by MVC, not the security chain.
		for (Role role : Role.values()) {
			int status = mockMvc.perform(post("/api/v1/health").header("Authorization", bearer(role)))
				.andReturn().getResponse().getStatus();
			assertThat(status).isNotIn(401, 403);
		}
	}

	@Test
	void publicReadsStillNeedNoToken() throws Exception {
		mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/api-docs")).andExpect(status().isOk());
	}

	private String bearer(Role role) {
		return "Bearer " + tokens.issue(UUID.randomUUID(), role).value();
	}

	private String tokenWithRole(String role) {
		Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		var claims = JwtClaimsSet.builder()
			.issuer(JwtConfig.ISSUER)
			.subject(UUID.randomUUID().toString())
			.issuedAt(now)
			.expiresAt(now.plusSeconds(600));
		if (role != null) {
			claims.claim(JwtConfig.ROLE_CLAIM, role);
		}
		var header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}
}
