package com.musicboxd.api.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MBD-13 acceptance: the demo endpoint rejects a caller past its configured per-user
 * limit. The policy is overridden here so the test owns the numbers.
 */
@SpringBootTest(properties = {
	"musicboxd.rate-limit.policies.demo.capacity=2",
	"musicboxd.rate-limit.policies.demo.refill-period=1h" })
@AutoConfigureMockMvc
class DemoRateLimitControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void rejectsCallerPastItsLimitAndLeavesOthersAlone() throws Exception {
		for (int i = 0; i < 2; i++) {
			mockMvc.perform(get("/api/v1/demo/rate-limited").header("X-Demo-User", "alice"))
				.andExpect(status().isOk());
		}
		mockMvc.perform(get("/api/v1/demo/rate-limited").header("X-Demo-User", "alice"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.status").value(429));
		// Same IP, different user: independent bucket.
		mockMvc.perform(get("/api/v1/demo/rate-limited").header("X-Demo-User", "bob"))
			.andExpect(status().isOk());
	}

	@Test
	void missingDemoUserIsABadRequestNotAnUnlimitedPass() throws Exception {
		mockMvc.perform(get("/api/v1/demo/rate-limited")).andExpect(status().isBadRequest());
	}

	@Test
	void healthIsNotRateLimited() throws Exception {
		for (int i = 0; i < 10; i++) {
			mockMvc.perform(get("/api/v1/health")).andExpect(status().isOk());
		}
	}
}
