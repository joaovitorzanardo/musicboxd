package com.musicboxd.api.catalog;

import static com.musicboxd.api.catalog.CatalogFixtures.createAlbum;
import static com.musicboxd.api.catalog.CatalogFixtures.uuidAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

/**
 * MBD-29 Verify: Staff edits an Album, adds a Song to it, and hides the Album; the hidden Album
 * disappears from search but still opens at its direct link; no delete endpoint exists.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CatalogEditAcceptanceTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void staffEditsAddsASongAndHidesTheAlbumWhichStillOpensByLink() throws Exception {
		String staff = StaffTokens.bearer(tokens, Role.STAFF);
		String created = createAlbum(mockMvc, tokens, List.of(), "Dreams");
		UUID id = uuidAt(created, "$.id");
		UUID dreams = uuidAt(created, "$.songs[0].id");
		String title = "Rumours (Deluxe) " + UUID.randomUUID();

		// Edit the album and add a song to it, in one save.
		String saved = mockMvc.perform(put("/api/v1/admin/catalog/albums/{id}", id)
				.header("Authorization", staff)
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"title":"%s","artist":"Fleetwood Mac","releaseYear":1977,"genreIds":[],"hidden":false,
						 "songs":[{"id":"%s","title":"Dreams","hidden":false},
						          {"title":"Silver Springs","durationSeconds":287,"hidden":false}]}"""
					.formatted(title, dreams)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.songs", hasSize(2)))
			.andReturn().getResponse().getContentAsString();
		UUID silverSprings = uuidAt(saved, "$.songs[1].id");

		// Hide it, as the catalog table's "Ocultar" does.
		mockMvc.perform(put("/api/v1/admin/catalog/albums/{id}/hidden", id)
				.header("Authorization", staff)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"hidden\":true}"))
			.andExpect(status().isNoContent());

		// Search (MBD-34) leaves out rows with this flag; it is stored here.
		assertThat(jdbc.sql("SELECT hidden FROM catalog.albums WHERE id = :id").param("id", id)
			.query(Boolean.class).single()).isTrue();

		// The direct links keep working for anyone, with no token.
		mockMvc.perform(get("/api/v1/catalog/albums/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value(title))
			.andExpect(jsonPath("$.hidden").value(true))
			.andExpect(jsonPath("$.songs", hasSize(2)))
			.andExpect(jsonPath("$.songs[1].title").value("Silver Springs"));
		mockMvc.perform(get("/api/v1/catalog/songs/{id}", silverSprings))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.album.id").value(id.toString()))
			.andExpect(jsonPath("$.album.hidden").value(true));
	}

	@Test
	void nothingInTheCatalogCanBeDeleted() throws Exception {
		String staff = StaffTokens.bearer(tokens, Role.STAFF);
		String created = createAlbum(mockMvc, tokens, List.of(), "Dreams");
		UUID id = uuidAt(created, "$.id");
		UUID song = uuidAt(created, "$.songs[0].id");

		for (String path : List.of("/api/v1/admin/catalog/albums/" + id, "/api/v1/admin/catalog/songs/" + song,
				"/api/v1/admin/catalog/genres/" + UUID.randomUUID(), "/api/v1/catalog/albums/" + id,
				"/api/v1/catalog/songs/" + song)) {
			int status = mockMvc.perform(delete(path).header("Authorization", staff)).andReturn().getResponse().getStatus();
			assertThat(status).as("DELETE %s", path).isIn(404, 405);
		}
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.songs WHERE id = :id AND album_id = :album")
			.param("id", song).param("album", id).query(Long.class).single()).isEqualTo(1);
	}
}
