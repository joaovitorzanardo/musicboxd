package com.musicboxd.api.health;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.musicboxd.api.security.JwtConfig;
import com.musicboxd.api.security.SecurityConfig;

/**
 * Proves the exact contract nginx proxies to (AD-7): {@code GET /api/v1/health}
 * returns 200 and {@code {"status":"ok"}}.
 */
@WebMvcTest(HealthController.class)
@Import({ SecurityConfig.class, JwtConfig.class })
class HealthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void healthEndpointReturnsOkStatus() throws Exception {
		mockMvc.perform(get("/api/v1/health"))
			.andExpect(status().isOk())
			.andExpect(content().contentType(MediaType.APPLICATION_JSON))
			.andExpect(content().json("{\"status\":\"ok\"}"));
	}
}
