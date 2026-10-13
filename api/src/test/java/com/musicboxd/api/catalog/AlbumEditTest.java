package com.musicboxd.api.catalog;

import static com.musicboxd.api.catalog.CatalogFixtures.createAlbum;
import static com.musicboxd.api.catalog.CatalogFixtures.genre;
import static com.musicboxd.api.catalog.CatalogFixtures.uuidAt;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

/** The editor's "Salvar": the whole Album state in one call, applied all or nothing. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AlbumEditTest {

	private static final String ALBUM = "/api/v1/admin/catalog/albums/{id}";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void staffEditsTitleArtistYearGenresAndVisibility() throws Exception {
		UUID rock = genre(jdbc, "Rock", false);
		UUID pop = genre(jdbc, "Pop", false);
		String created = createAlbum(mockMvc, tokens, List.of(rock), "Dreams");
		UUID id = uuidAt(created, "$.id");
		UUID dreams = uuidAt(created, "$.songs[0].id");
		String title = "Tusk " + UUID.randomUUID();

		edit(Role.STAFF, id, """
				{"title":"  %s ","artist":" Fleetwood Mac ","releaseYear":1979,"genreIds":["%s"],"hidden":true,
				 "songs":[{"id":"%s","title":"Dreams","hidden":false}]}""".formatted(title, pop, dreams))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").value(id.toString()))
			.andExpect(jsonPath("$.title").value(title))
			.andExpect(jsonPath("$.artist").value("Fleetwood Mac"))
			.andExpect(jsonPath("$.releaseYear").value(1979))
			.andExpect(jsonPath("$.hidden").value(true))
			.andExpect(jsonPath("$.genres", hasSize(1)))
			.andExpect(jsonPath("$.genres[0].id").value(pop.toString()))
			.andExpect(jsonPath("$.songs", hasSize(1)))
			.andExpect(jsonPath("$.songs[0].id").value(dreams.toString()));

		assertThat(albumTitle(id)).isEqualTo(title);
		assertThat(genreIdsOf(id)).containsExactly(pop);
	}

	@Test
	void staffAddsSongsToTheAlbum() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "Second Hand News", "Dreams");
		UUID id = uuidAt(created, "$.id");
		UUID a = uuidAt(created, "$.songs[0].id");
		UUID b = uuidAt(created, "$.songs[1].id");

		edit(Role.STAFF, id, body(titleOf(created), "", false,
				track(a, "Second Hand News"), track(b, "Dreams"), newTrack("Go Your Own Way")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.songs", hasSize(3)))
			.andExpect(jsonPath("$.songs[2].id").isString())
			.andExpect(jsonPath("$.songs[2].trackNumber").value(3))
			.andExpect(jsonPath("$.songs[2].title").value("Go Your Own Way"))
			.andExpect(jsonPath("$.songs[2].hidden").value(false));

		assertThat(songTitles(id)).containsExactly("Second Hand News", "Dreams", "Go Your Own Way");
	}

	@Test
	void reorderingRenumbersTheTracklist() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One", "Two", "Three");
		UUID id = uuidAt(created, "$.id");
		UUID one = uuidAt(created, "$.songs[0].id");
		UUID two = uuidAt(created, "$.songs[1].id");
		UUID three = uuidAt(created, "$.songs[2].id");

		// A new song at the top and the old last track moved up: every number changes hands.
		edit(Role.STAFF, id, body(titleOf(created), "", false,
				newTrack("Zero"), track(three, "Three"), track(one, "One"), track(two, "Two")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.songs[1].id").value(three.toString()))
			.andExpect(jsonPath("$.songs[1].trackNumber").value(2));

		assertThat(songTitles(id)).containsExactly("Zero", "Three", "One", "Two");
	}

	@Test
	void staffEditsAndHidesASongInTheTracklist() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "Dreams", "Songbird");
		UUID id = uuidAt(created, "$.id");
		UUID dreams = uuidAt(created, "$.songs[0].id");
		UUID songbird = uuidAt(created, "$.songs[1].id");

		edit(Role.STAFF, id, body(titleOf(created), "", false,
				"{\"id\":\"%s\",\"title\":\"Dreams (Remastered)\",\"durationSeconds\":257,\"hidden\":false}".formatted(dreams),
				"{\"id\":\"%s\",\"title\":\"Songbird\",\"hidden\":true}".formatted(songbird)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.songs[0].title").value("Dreams (Remastered)"))
			.andExpect(jsonPath("$.songs[0].durationSeconds").value(257))
			.andExpect(jsonPath("$.songs[1].hidden").value(true));

		mockMvc.perform(get("/api/v1/catalog/albums/{id}", id))
			.andExpect(jsonPath("$.songs", hasSize(1)))
			.andExpect(jsonPath("$.songs[0].id").value(dreams.toString()));
	}

	@Test
	void leavingOutASongIs422AndChangesNothing() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One", "Two");
		UUID id = uuidAt(created, "$.id");
		UUID one = uuidAt(created, "$.songs[0].id");
		UUID two = uuidAt(created, "$.songs[1].id");

		edit(Role.STAFF, id, body("Renamed " + UUID.randomUUID(), "", false, track(one, "One")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:tracklist-mismatch"))
			.andExpect(jsonPath("$.missingSongIds", hasSize(1)))
			.andExpect(jsonPath("$.missingSongIds[0]").value(two.toString()))
			.andExpect(jsonPath("$.unknownSongIds", hasSize(0)))
			.andExpect(jsonPath("$.repeatedSongIds", hasSize(0)));

		assertThat(albumTitle(id)).isEqualTo(titleOf(created));
		assertThat(songTitles(id)).containsExactly("One", "Two");
	}

	@Test
	void aSongOfAnotherAlbumIs422AndStaysWhereItIs() throws Exception {
		String mine = createAlbum(mockMvc, tokens, List.of(), "One");
		String theirs = createAlbum(mockMvc, tokens, List.of(), "Elsewhere");
		UUID id = uuidAt(mine, "$.id");
		UUID one = uuidAt(mine, "$.songs[0].id");
		UUID other = uuidAt(theirs, "$.songs[0].id");

		edit(Role.STAFF, id, body(titleOf(mine), "", false, track(one, "One"), track(other, "Elsewhere")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.unknownSongIds[0]").value(other.toString()));

		assertThat(jdbc.sql("SELECT album_id FROM catalog.songs WHERE id = :id").param("id", other)
			.query(UUID.class).single()).isEqualTo(uuidAt(theirs, "$.id"));
		assertThat(songTitles(id)).containsExactly("One");
	}

	@Test
	void aRepeatedSongIs422() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One");
		UUID one = uuidAt(created, "$.songs[0].id");
		edit(Role.STAFF, uuidAt(created, "$.id"), body(titleOf(created), "", false, track(one, "One"), track(one, "One")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.repeatedSongIds[0]").value(one.toString()));
	}

	@Test
	void aGenreTheAlbumAlreadyHasStaysAfterItIsHidden() throws Exception {
		UUID kept = genre(jdbc, "Psicodelia", false);
		String created = createAlbum(mockMvc, tokens, List.of(kept), "Dreams");
		jdbc.sql("UPDATE catalog.genres SET hidden = true WHERE id = :id").param("id", kept).update();

		edit(Role.STAFF, uuidAt(created, "$.id"), body(titleOf(created), ids(kept), false,
				track(uuidAt(created, "$.songs[0].id"), "Dreams")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.genres[0].id").value(kept.toString()))
			.andExpect(jsonPath("$.genres[0].hidden").value(true));
	}

	@Test
	void aNewlyPickedHiddenGenreIs422() throws Exception {
		UUID hidden = genre(jdbc, "Vaporwave", true);
		String created = createAlbum(mockMvc, tokens, List.of(), "Dreams");
		UUID id = uuidAt(created, "$.id");

		edit(Role.STAFF, id, body(titleOf(created), ids(hidden), false, track(uuidAt(created, "$.songs[0].id"), "Dreams")))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:unknown-genre"))
			.andExpect(jsonPath("$.genreIds[0]").value(hidden.toString()));
		assertThat(genreIdsOf(id)).isEmpty();
	}

	@Test
	void unknownAlbumIs404() throws Exception {
		edit(Role.STAFF, UUID.randomUUID(), body("Ghost", "", false, newTrack("Boo")))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	@Test
	void invalidEditsAre400AndChangeNothing() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One");
		UUID id = uuidAt(created, "$.id");
		UUID one = uuidAt(created, "$.songs[0].id");
		String t = track(one, "One");
		int tooLate = Year.now(ZoneOffset.UTC).getValue() + 2;
		String[] bodies = {
			body("   ", "", false, t),
			"{\"title\":\"T\",\"artist\":\"A\",\"songs\":[" + t + "]}",
			body("T", "", false),
			"{\"title\":\"T\",\"artist\":\"A\",\"hidden\":false,\"songs\":[{\"id\":\"" + one + "\",\"title\":\"One\"}]}",
			"{\"title\":\"T\",\"artist\":\"A\",\"hidden\":false,\"songs\":[{\"id\":\"" + one + "\",\"title\":\" \",\"hidden\":false}]}",
			"{\"title\":\"T\",\"artist\":\"A\",\"hidden\":false,\"releaseYear\":" + tooLate + ",\"songs\":[" + t + "]}",
		};
		for (String b : bodies) {
			edit(Role.STAFF, id, b)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
		assertThat(albumTitle(id)).isEqualTo(titleOf(created));
	}

	@Test
	void userTokenGets403AndChangesNothing() throws Exception {
		String created = createAlbum(mockMvc, tokens, List.of(), "One");
		UUID id = uuidAt(created, "$.id");
		edit(Role.USER, id, body("Hijacked", "", true, track(uuidAt(created, "$.songs[0].id"), "One")))
			.andExpect(status().isForbidden());
		assertThat(albumTitle(id)).isEqualTo(titleOf(created));
	}

	private ResultActions edit(Role role, UUID id, String body) throws Exception {
		return mockMvc.perform(put(ALBUM, id)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	/** {@code genres} and {@code tracks} are JSON fragments: see {@link #ids}, {@link #track}, {@link #newTrack}. */
	private static String body(String title, String genres, boolean hidden, String... tracks) {
		return "{\"title\":\"%s\",\"artist\":\"Fleetwood Mac\",\"releaseYear\":1977,\"genreIds\":[%s],\"hidden\":%s,\"songs\":[%s]}"
			.formatted(title, genres, hidden, String.join(",", tracks));
	}

	private static String ids(UUID... ids) {
		return Stream.of(ids).map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
	}

	private static String track(UUID id, String title) {
		return "{\"id\":\"%s\",\"title\":\"%s\",\"hidden\":false}".formatted(id, title);
	}

	private static String newTrack(String title) {
		return "{\"title\":\"%s\",\"hidden\":false}".formatted(title);
	}

	private static String titleOf(String albumJson) {
		return JsonPath.read(albumJson, "$.title");
	}

	private String albumTitle(UUID id) {
		return jdbc.sql("SELECT title FROM catalog.albums WHERE id = :id").param("id", id).query(String.class).single();
	}

	private List<String> songTitles(UUID albumId) {
		return jdbc.sql("SELECT title FROM catalog.songs WHERE album_id = :id ORDER BY track_number")
			.param("id", albumId).query(String.class).list();
	}

	private List<UUID> genreIdsOf(UUID albumId) {
		return jdbc.sql("SELECT genre_id FROM catalog.album_genres WHERE album_id = :id")
			.param("id", albumId).query(UUID.class).list();
	}
}
