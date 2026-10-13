package com.musicboxd.api.catalog;

import static com.musicboxd.api.catalog.CatalogFixtures.createAlbum;
import static com.musicboxd.api.catalog.CatalogFixtures.uuidAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** The catalog table's "Ocultar"/"Reexibir": toggles visibility, never deletes (UX, AD-12). */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AlbumVisibilityTest {

	private static final String ALBUM_HIDDEN = "/api/v1/admin/catalog/albums/{id}/hidden";
	private static final String SONG_HIDDEN = "/api/v1/admin/catalog/songs/{id}/hidden";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void staffHidesAndUnhidesAnAlbum() throws Exception {
		UUID id = uuidAt(createAlbum(mockMvc, tokens, List.of(), "Dreams"), "$.id");

		putHidden(ALBUM_HIDDEN, id, Role.STAFF, "{\"hidden\":true}").andExpect(status().isNoContent());
		assertThat(albumHidden(id)).isTrue();
		mockMvc.perform(get("/api/v1/catalog/albums/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hidden").value(true));

		putHidden(ALBUM_HIDDEN, id, Role.STAFF, "{\"hidden\":false}").andExpect(status().isNoContent());
		assertThat(albumHidden(id)).isFalse();
	}

	@Test
	void hidingTwiceIsFine() throws Exception {
		UUID id = uuidAt(createAlbum(mockMvc, tokens, List.of(), "Dreams"), "$.id");
		putHidden(ALBUM_HIDDEN, id, Role.STAFF, "{\"hidden\":true}").andExpect(status().isNoContent());
		putHidden(ALBUM_HIDDEN, id, Role.STAFF, "{\"hidden\":true}").andExpect(status().isNoContent());
		assertThat(albumHidden(id)).isTrue();
	}

	@Test
	void staffHidesASongAndItLeavesTheAlbumPage() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One", "Two");
		UUID album = uuidAt(created, "$.id");
		UUID two = uuidAt(created, "$.songs[1].id");

		putHidden(SONG_HIDDEN, two, Role.STAFF, "{\"hidden\":true}").andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/catalog/albums/{id}", album)).andExpect(jsonPath("$.songs", hasSize(1)));
		mockMvc.perform(get("/api/v1/catalog/songs/{id}", two))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hidden").value(true));
		mockMvc.perform(get("/api/v1/admin/catalog/albums/{id}", album)
				.header("Authorization", StaffTokens.bearer(tokens, Role.STAFF)))
			.andExpect(jsonPath("$.songs[1].hidden").value(true));

		putHidden(SONG_HIDDEN, two, Role.STAFF, "{\"hidden\":false}").andExpect(status().isNoContent());
		mockMvc.perform(get("/api/v1/catalog/albums/{id}", album)).andExpect(jsonPath("$.songs", hasSize(2)));
	}

	@Test
	void unknownIdsAre404() throws Exception {
		for (String path : List.of(ALBUM_HIDDEN, SONG_HIDDEN)) {
			putHidden(path, UUID.randomUUID(), Role.STAFF, "{\"hidden\":true}")
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
	}

	@Test
	void hiddenIsRequired() throws Exception {
		UUID id = uuidAt(createAlbum(mockMvc, tokens, List.of(), "Dreams"), "$.id");
		for (String body : new String[] { "{}", "{\"hidden\":null}" }) {
			putHidden(ALBUM_HIDDEN, id, Role.STAFF, body)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		assertThat(albumHidden(id)).isFalse();
	}

	@Test
	void userTokenGets403AndChangesNothing() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "Dreams");
		UUID album = uuidAt(created, "$.id");
		UUID song = uuidAt(created, "$.songs[0].id");
		putHidden(ALBUM_HIDDEN, album, Role.USER, "{\"hidden\":true}").andExpect(status().isForbidden());
		putHidden(SONG_HIDDEN, song, Role.USER, "{\"hidden\":true}").andExpect(status().isForbidden());
		assertThat(albumHidden(album)).isFalse();
		assertThat(jdbc.sql("SELECT hidden FROM catalog.songs WHERE id = :id").param("id", song)
			.query(Boolean.class).single()).isFalse();
	}

	private ResultActions putHidden(String path, UUID id, Role role, String body) throws Exception {
		return mockMvc.perform(put(path, id)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	private boolean albumHidden(UUID id) {
		return jdbc.sql("SELECT hidden FROM catalog.albums WHERE id = :id").param("id", id)
			.query(Boolean.class).single();
	}
}
