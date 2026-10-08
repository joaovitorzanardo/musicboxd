package com.musicboxd.api.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import com.musicboxd.api.TestcontainersConfiguration;

/**
 * The demo route rides the springdoc pipeline (AD-7), so the generated TypeScript client
 * sees it, its 429 response included.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class DemoOpenApiTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void demoEndpointAndItsTooManyRequestsResponseAreInTheContract() throws Exception {
		mockMvc.perform(get("/api/v1/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/v1/demo/rate-limited'].get.responses['200']").exists())
			.andExpect(jsonPath("$.paths['/api/v1/demo/rate-limited'].get.responses['429']").exists());
	}
}
