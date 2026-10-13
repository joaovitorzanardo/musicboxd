package com.musicboxd.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

import com.jayway.jsonpath.JsonPath;
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

	@Test
	void staffRenamesAGenre() throws Exception {
		String id = createdId("Rock " + UUID.randomUUID());
		String renamed = "Hard Rock " + UUID.randomUUID();
		edit(Role.STAFF, id, "{\"name\":\"  " + renamed + "  \"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.name").value(renamed))
			.andExpect(jsonPath("$.hidden").value(false));
		assertThat(stored(id).name()).isEqualTo(renamed);
	}

	@Test
	void renameMayChangeOnlyTheCase() throws Exception {
		String name = "rock " + UUID.randomUUID();
		String id = createdId(name);
		edit(Role.STAFF, id, "{\"name\":\"" + name.toUpperCase() + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value(name.toUpperCase()));
	}

	@Test
	void renameToAnotherGenresNameIs409AndChangesNothing() throws Exception {
		String taken = "Jazz " + UUID.randomUUID();
		createdId(taken);
		String name = "Blues " + UUID.randomUUID();
		String id = createdId(name);
		edit(Role.STAFF, id, "{\"name\":\" " + taken.toLowerCase() + " \"}")
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:genre-taken"));
		assertThat(stored(id).name()).isEqualTo(name);
	}

	@Test
	void staffHidesAndShowsAGenreAgain() throws Exception {
		String name = "MPB " + UUID.randomUUID();
		String id = createdId(name);

		edit(Role.STAFF, id, "{\"hidden\":true}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value(name))
			.andExpect(jsonPath("$.hidden").value(true));
		createAlbumWith(id)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.genreIds[0]").value(id));

		edit(Role.STAFF, id, "{\"hidden\":false}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hidden").value(false));
		createAlbumWith(id).andExpect(status().isCreated());
	}

	@Test
	void invalidEditsAre400AndChangeNothing() throws Exception {
		String name = "Funk " + UUID.randomUUID();
		String id = createdId(name);
		for (String body : new String[] { "{\"name\":\"   \"}", "{\"name\":\"" + "x".repeat(51) + "\"}", "{}",
				"{\"name\":null,\"hidden\":null}" }) {
			edit(Role.STAFF, id, body)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		assertThat(stored(id)).isEqualTo(new GenreView(UUID.fromString(id), name, false));
	}

	@Test
	void unknownGenreIs404AndMalformedIdIs400() throws Exception {
		edit(Role.STAFF, UUID.randomUUID().toString(), "{\"name\":\"Anything\"}")
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		edit(Role.STAFF, "not-a-uuid", "{\"name\":\"Anything\"}")
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void userTokenCannotEditAGenre() throws Exception {
		String name = "Samba " + UUID.randomUUID();
		String id = createdId(name);
		edit(Role.USER, id, "{\"name\":\"Hijacked " + UUID.randomUUID() + "\",\"hidden\":true}")
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		assertThat(stored(id)).isEqualTo(new GenreView(UUID.fromString(id), name, false));
	}

	private ResultActions create(Role role, String body) throws Exception {
		return mockMvc.perform(post(GENRES)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private String createdId(String name) throws Exception {
		String json = create(Role.STAFF, "{\"name\":\"" + name + "\"}")
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.id");
	}

	private ResultActions edit(Role role, String id, String body) throws Exception {
		return mockMvc.perform(patch(GENRES + "/" + id)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions createAlbumWith(String genreId) throws Exception {
		return mockMvc.perform(post("/api/v1/admin/catalog/albums")
			.header("Authorization", StaffTokens.bearer(tokens, Role.STAFF))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"Album\",\"artist\":\"Artist\",\"genreIds\":[\"" + genreId
					+ "\"],\"songs\":[{\"title\":\"Song\"}]}"));
	}

	private GenreView stored(String id) {
		return jdbc.sql("SELECT id, name, hidden FROM catalog.genres WHERE id = :id")
			.param("id", UUID.fromString(id))
			.query(GenreView.class).single();
	}

	private long countNamed(String name) {
		return jdbc.sql("SELECT count(*) FROM catalog.genres WHERE name = :name").param("name", name)
			.query(Long.class).single();
	}
}
