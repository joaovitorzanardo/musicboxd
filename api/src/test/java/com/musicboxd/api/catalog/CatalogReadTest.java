package com.musicboxd.api.catalog;

import static com.musicboxd.api.catalog.CatalogFixtures.createAlbum;
import static com.musicboxd.api.catalog.CatalogFixtures.uuidAt;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

import com.jayway.jsonpath.JsonPath;
import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

/** AD-12: hidden items leave search (MBD-34) but stay resolvable by id, for anyone. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogReadTest {

	private static final String PUBLIC_ALBUM = "/api/v1/catalog/albums/{id}";
	private static final String PUBLIC_SONG = "/api/v1/catalog/songs/{id}";
	private static final String ADMIN_ALBUM = "/api/v1/admin/catalog/albums/{id}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void anyoneOpensAnAlbumByIdWithSongsInTrackOrder() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "Second Hand News", "Dreams");
		UUID id = uuidAt(created, "$.id");
		mockMvc.perform(get(PUBLIC_ALBUM, id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.title").value(JsonPath.<String>read(created, "$.title")))
			.andExpect(jsonPath("$.artist").value("Fleetwood Mac"))
			.andExpect(jsonPath("$.releaseYear").value(1977))
			.andExpect(jsonPath("$.hidden").value(false))
			.andExpect(jsonPath("$.createdAt").value(JsonPath.<String>read(created, "$.createdAt")))
			.andExpect(jsonPath("$.songs", hasSize(2)))
			.andExpect(jsonPath("$.songs[0].trackNumber").value(1))
			.andExpect(jsonPath("$.songs[0].title").value("Second Hand News"))
			.andExpect(jsonPath("$.songs[1].trackNumber").value(2))
			.andExpect(jsonPath("$.songs[1].title").value("Dreams"));
	}

	@Test
	void genresAreListedByName() throws Exception {
		UUID zydeco = CatalogFixtures.genre(jdbc, "Zydeco", false);
		UUID ambient = CatalogFixtures.genre(jdbc, "Ambient", false);
		UUID id = uuidAt(createAlbum(mockMvc, tokens, List.of(zydeco, ambient), "Dreams"), "$.id");
		mockMvc.perform(get(PUBLIC_ALBUM, id))
			.andExpect(jsonPath("$.genres", hasSize(2)))
			.andExpect(jsonPath("$.genres[0].id").value(ambient.toString()))
			.andExpect(jsonPath("$.genres[1].id").value(zydeco.toString()));
	}

	@Test
	void hiddenAlbumStillOpensByLink() throws Exception {
		UUID id = uuidAt(createAlbum(mockMvc, tokens, List.of(), "Dreams"), "$.id");
		hideAlbum(id);
		mockMvc.perform(get(PUBLIC_ALBUM, id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hidden").value(true))
			.andExpect(jsonPath("$.songs", hasSize(1)));
	}

	@Test
	void hiddenSongIsLeftOffTheAlbumPageButOpensByItsOwnLink() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One", "Two", "Three");
		UUID album = uuidAt(created, "$.id");
		UUID two = uuidAt(created, "$.songs[1].id");
		hideSong(two);

		mockMvc.perform(get(PUBLIC_ALBUM, album))
			.andExpect(jsonPath("$.songs", hasSize(2)))
			.andExpect(jsonPath("$.songs[0].trackNumber").value(1))
			.andExpect(jsonPath("$.songs[1].trackNumber").value(3));
		mockMvc.perform(get(PUBLIC_SONG, two))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(two.toString()))
			.andExpect(jsonPath("$.trackNumber").value(2))
			.andExpect(jsonPath("$.title").value("Two"))
			.andExpect(jsonPath("$.hidden").value(true))
			.andExpect(jsonPath("$.album.id").value(album.toString()))
			.andExpect(jsonPath("$.album.title").value(JsonPath.<String>read(created, "$.title")))
			.andExpect(jsonPath("$.album.artist").value("Fleetwood Mac"))
			.andExpect(jsonPath("$.album.releaseYear").value(1977))
			.andExpect(jsonPath("$.album.hidden").value(false));
	}

	@Test
	void songOfAHiddenAlbumOpensByLink() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "Dreams");
		hideAlbum(uuidAt(created, "$.id"));
		mockMvc.perform(get(PUBLIC_SONG, uuidAt(created, "$.songs[0].id")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.hidden").value(false))
			.andExpect(jsonPath("$.album.hidden").value(true));
	}

	@Test
	void staffEditorSeesHiddenSongs() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One", "Two", "Three");
		UUID album = uuidAt(created, "$.id");
		hideSong(uuidAt(created, "$.songs[1].id"));
		mockMvc.perform(get(ADMIN_ALBUM, album).header("Authorization", StaffTokens.bearer(tokens, Role.STAFF)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.songs", hasSize(3)))
			.andExpect(jsonPath("$.songs[1].hidden").value(true));
	}

	@Test
	void userTokenCannotUseTheAdminRead() throws Exception {
		UUID album = uuidAt(createAlbum(mockMvc, tokens, List.of(), "Dreams"), "$.id");
		mockMvc.perform(get(ADMIN_ALBUM, album).header("Authorization", StaffTokens.bearer(tokens, Role.USER)))
			.andExpect(status().isForbidden());
	}

	@Test
	void unknownIdsAre404() throws Exception {
		UUID missing = UUID.randomUUID();
		for (String path : List.of(PUBLIC_ALBUM, PUBLIC_SONG)) {
			mockMvc.perform(get(path, missing))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		mockMvc.perform(get(ADMIN_ALBUM, missing).header("Authorization", StaffTokens.bearer(tokens, Role.STAFF)))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void malformedIdIs400() throws Exception {
		mockMvc.perform(get("/api/v1/catalog/albums/not-a-uuid"))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	private void hideAlbum(UUID id) {
		jdbc.sql("UPDATE catalog.albums SET hidden = true WHERE id = :id").param("id", id).update();
	}

	private void hideSong(UUID id) {
		jdbc.sql("UPDATE catalog.songs SET hidden = true WHERE id = :id").param("id", id).update();
	}
}
