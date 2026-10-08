package com.musicboxd.api.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.accounts.TokenService;

/** AD-7: public GETs need no token; writes need one; the contract documents the bearer scheme. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityRulesTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Test
	void publicGetsNeedNoToken() throws Exception {
		mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/api-docs")).andExpect(status().isOk());
	}

	@Test
	void unauthenticatedWriteIsRejectedBeforeRouting() throws Exception {
		mockMvc.perform(post("/api/v1/health")).andExpect(status().isUnauthorized());
	}

	@Test
	void bearerTokensStillAuthenticateUnderTheAuthPrefix() throws Exception {
		// Only the public auth endpoints ignore bearer headers; a future authenticated /api/v1/auth route
		// (a password change) must still see a valid token. No such route exists, so 404.
		String token = tokens.issue(UUID.randomUUID()).value();
		mockMvc.perform(post("/api/v1/auth/not-a-route").header("Authorization", "Bearer " + token))
			.andExpect(status().isNotFound());
	}

	@Test
	void contractListsAuthRoutesAndTheBearerScheme() throws Exception {
		mockMvc.perform(get("/api/v1/api-docs"))
			.andExpect(jsonPath("$.paths['/api/v1/auth/register'].post").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['401']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['403']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/verify'].get.responses['200']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/verification-email'].post.responses['202']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post.responses['200']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].post.responses['401']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/auth/refresh'].delete.responses['204']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/accounts/me'].get.security[0].bearer").exists())
			.andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"));
	}
}
