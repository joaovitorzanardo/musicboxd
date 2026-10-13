package com.musicboxd.api.catalog;

import static com.musicboxd.api.catalog.CatalogFixtures.createAlbum;
import static com.musicboxd.api.catalog.CatalogFixtures.genre;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

/** The admin "Gêneros" list: add, rename, hide; nothing is deleted (UX, AD-12). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class GenreAdminTest {

	private static final String GENRES = "/api/v1/admin/catalog/genres";
	private static final String GENRE = GENRES + "/{id}";
	private static final String GENRE_HIDDEN = GENRE + "/hidden";

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
		UUID id = genre(jdbc, "Rock", false);
		String renamed = "Hard Rock " + UUID.randomUUID();
		rename(Role.STAFF, id, "{\"name\":\"  " + renamed + "  \"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.name").value(renamed))
			.andExpect(jsonPath("$.hidden").value(false));
		assertThat(stored(id).name()).isEqualTo(renamed);
	}

	@Test
	void renameMayChangeOnlyTheCase() throws Exception {
		UUID id = genre(jdbc, "rock", false);
		String upper = stored(id).name().toUpperCase();
		rename(Role.STAFF, id, "{\"name\":\"" + upper + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value(upper));
	}

	@Test
	void renamingAHiddenGenreKeepsItHidden() throws Exception {
		UUID id = genre(jdbc, "Axé", true);
		rename(Role.STAFF, id, "{\"name\":\"Axé music " + UUID.randomUUID() + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hidden").value(true));
		assertThat(stored(id).hidden()).isTrue();
	}

	@Test
	void renameToAnotherGenresNameIs409AndChangesNothing() throws Exception {
		String taken = stored(genre(jdbc, "Jazz", false)).name();
		UUID id = genre(jdbc, "Blues", false);
		GenreView before = stored(id);
		rename(Role.STAFF, id, "{\"name\":\" " + taken.toLowerCase() + " \"}")
			.andExpect(status().isConflict())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:genre-taken"));
		assertThat(stored(id)).isEqualTo(before);
	}

	@Test
	void invalidRenamesAre400AndChangeNothing() throws Exception {
		UUID id = genre(jdbc, "Funk", false);
		GenreView before = stored(id);
		for (String body : new String[] { "{\"name\":\"   \"}", "{\"name\":\"" + "x".repeat(51) + "\"}", "{}",
				"{\"name\":null}" }) {
			rename(Role.STAFF, id, body)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		assertThat(stored(id)).isEqualTo(before);
	}

	/** Hidden genres leave the options for new picks (AlbumAdminTest.hiddenGenreIsRefusedWith422); shown again, they are back. */
	@Test
	void staffHidesAndShowsAGenreAgain() throws Exception {
		UUID id = genre(jdbc, "MPB", false);

		setHidden(Role.STAFF, id, "{\"hidden\":true}").andExpect(status().isNoContent());
		setHidden(Role.STAFF, id, "{\"hidden\":true}").andExpect(status().isNoContent());
		assertThat(stored(id).hidden()).isTrue();

		setHidden(Role.STAFF, id, "{\"hidden\":false}").andExpect(status().isNoContent());
		assertThat(stored(id).hidden()).isFalse();
		createAlbum(mockMvc, tokens, List.of(id), "Águas de Março");
	}

	@Test
	void missingHiddenIs400() throws Exception {
		UUID id = genre(jdbc, "Forró", false);
		setHidden(Role.STAFF, id, "{}")
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		assertThat(stored(id).hidden()).isFalse();
	}

	@Test
	void unknownGenreIs404AndMalformedIdIs400() throws Exception {
		UUID unknown = UUID.randomUUID();
		rename(Role.STAFF, unknown, "{\"name\":\"Anything\"}").andExpect(status().isNotFound());
		setHidden(Role.STAFF, unknown, "{\"hidden\":true}").andExpect(status().isNotFound());
		rename(Role.STAFF, "not-a-uuid", "{\"name\":\"Anything\"}")
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void userTokenCannotRenameOrHideAGenre() throws Exception {
		UUID id = genre(jdbc, "Samba", false);
		GenreView before = stored(id);
		rename(Role.USER, id, "{\"name\":\"Hijacked " + UUID.randomUUID() + "\"}")
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		setHidden(Role.USER, id, "{\"hidden\":true}")
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		assertThat(stored(id)).isEqualTo(before);
	}

	private ResultActions create(Role role, String body) throws Exception {
		return mockMvc.perform(post(GENRES)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions rename(Role role, Object id, String body) throws Exception {
		return mockMvc.perform(put(GENRE, id)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private ResultActions setHidden(Role role, Object id, String body) throws Exception {
		return mockMvc.perform(put(GENRE_HIDDEN, id)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private GenreView stored(UUID id) {
		return jdbc.sql("SELECT id, name, hidden FROM catalog.genres WHERE id = :id")
			.param("id", id)
			.query(GenreView.class).single();
	}

	private long countNamed(String name) {
		return jdbc.sql("SELECT count(*) FROM catalog.genres WHERE name = :name").param("name", name)
			.query(Long.class).single();
	}
}
