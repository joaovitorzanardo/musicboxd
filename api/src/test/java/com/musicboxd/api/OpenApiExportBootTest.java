package com.musicboxd.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Guards the web image build's contract export (web/Dockerfile runs generateOpenApiDocs): the api must boot
 * and serve its OpenAPI document with migrations off and no reachable database.
 */
@SpringBootTest(properties = { "musicboxd.migrations.enabled=false",
		"spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable" })
@AutoConfigureMockMvc
class OpenApiExportBootTest {

	@Autowired
	private MockMvc mockMvc;

	@Test
	void apiDocsAreServedWithoutDatabase() throws Exception {
		mockMvc.perform(get("/api/v1/api-docs"))
			.andExpect(status().isOk())
			.andExpect(content().string(Matchers.containsString("/api/v1/auth/login")));
	}
}
