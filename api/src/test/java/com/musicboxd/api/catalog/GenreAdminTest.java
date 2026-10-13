package com.musicboxd.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GenreAdminTest {

	private static final String GENRES = "/api/v1/admin/catalog/genres";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void staffCreatesAGenre() throws Exception {
		String name = "Rock " + UUID.randomUUID();
		create(Role.STAFF, "{\"name\":\"" + name + "\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isString())
			.andExpect(jsonPath("$.name").value(name))
			.andExpect(jsonPath("$.hidden").value(false));
		assertThat(countNamed(name)).isEqualTo(1);
	}

	@Test
	void genreNameIsStrippedAndClashesIgnoringCase() throws Exception {
		String name = "Rock " + UUID.randomUUID();
		create(Role.STAFF, "{\"name\":\"  " + name + "  \"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value(name));
		create(Role.STAFF, "{\"name\":\" " + name.toUpperCase() + " \"}")
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:genre-taken"));
	}

	@Test
	void blankOrTooLongNamesAre400() throws Exception {
		for (String body : new String[] { "{\"name\":\"   \"}", "{}", "{\"name\":\"" + "x".repeat(51) + "\"}" }) {
			create(Role.STAFF, body)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
	}

	@Test
	void userTokenCannotCreateAGenre() throws Exception {
		String name = "Rock " + UUID.randomUUID();
		create(Role.USER, "{\"name\":\"" + name + "\"}")
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		assertThat(countNamed(name)).isZero();
	}

	private ResultActions create(Role role, String body) throws Exception {
		return mockMvc.perform(post(GENRES)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private long countNamed(String name) {
		return jdbc.sql("SELECT count(*) FROM catalog.genres WHERE name = :name").param("name", name)
			.query(Long.class).single();
	}
}
