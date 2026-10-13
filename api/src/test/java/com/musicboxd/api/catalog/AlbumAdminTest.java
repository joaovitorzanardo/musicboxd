package com.musicboxd.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Year;
import java.time.ZoneOffset;
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

/** MBD-28 acceptance: a STAFF token creates an Album; a USER token gets 403 on the same endpoint. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AlbumAdminTest {

	private static final String ALBUMS = "/api/v1/admin/catalog/albums";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TokenService tokens;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void staffCreatesAnAlbumWithSongsAndGenres() throws Exception {
		UUID rock = genre();
		UUID pop = genre();
		String title = uniqueTitle();
		String body = create(Role.STAFF, """
				{"title":"%s","artist":"Fleetwood Mac","releaseYear":1977,"genreIds":["%s","%s"],
				 "songs":[{"title":"Second Hand News","durationSeconds":163},{"title":"Dreams"}]}"""
			.formatted(title, rock, pop))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isString())
			.andExpect(jsonPath("$.title").value(title))
			.andExpect(jsonPath("$.artist").value("Fleetwood Mac"))
			.andExpect(jsonPath("$.releaseYear").value(1977))
			.andExpect(jsonPath("$.hidden").value(false))
			.andExpect(jsonPath("$.createdAt").isString())
			.andExpect(jsonPath("$.songs", hasSize(2)))
			.andExpect(jsonPath("$.songs[0].trackNumber").value(1))
			.andExpect(jsonPath("$.songs[0].title").value("Second Hand News"))
			.andExpect(jsonPath("$.songs[0].durationSeconds").value(163))
			.andExpect(jsonPath("$.songs[1].trackNumber").value(2))
			.andExpect(jsonPath("$.songs[1].durationSeconds").doesNotExist())
			.andExpect(jsonPath("$.genres[0].id").value(rock.toString()))
			.andExpect(jsonPath("$.genres[1].id").value(pop.toString()))
			.andReturn().getResponse().getContentAsString();

		UUID id = UUID.fromString(JsonPath.read(body, "$.id"));
		assertThat(jdbc.sql("SELECT title FROM catalog.songs WHERE album_id = :id ORDER BY track_number")
			.param("id", id).query(String.class).list()).containsExactly("Second Hand News", "Dreams");
		assertThat(jdbc.sql("SELECT genre_id FROM catalog.album_genres WHERE album_id = :id")
			.param("id", id).query(UUID.class).list()).containsExactlyInAnyOrder(rock, pop);
	}

	@Test
	void albumWithoutGenresOrYearIsFine() throws Exception {
		create(Role.STAFF, album(uniqueTitle(), ""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.releaseYear").doesNotExist())
			.andExpect(jsonPath("$.genres", hasSize(0)));
	}

	@Test
	void userTokenGets403AndWritesNothing() throws Exception {
		String title = uniqueTitle();
		create(Role.USER, album(title, ""))
			.andExpect(status().isForbidden())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.status").value(403));
		assertThat(albumsTitled(title)).isZero();
	}

	@Test
	void anonymousCallerGets401() throws Exception {
		mockMvc.perform(post(ALBUMS).contentType(MediaType.APPLICATION_JSON).content(album(uniqueTitle(), "")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void unknownGenreIsRefusedWith422AndWritesNothing() throws Exception {
		String title = uniqueTitle();
		UUID missing = UUID.randomUUID();
		create(Role.STAFF, album(title, "\"genreIds\":[\"" + genre() + "\",\"" + missing + "\"],"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.type").value("urn:musicboxd:problem:unknown-genre"))
			.andExpect(jsonPath("$.genreIds", hasSize(1)))
			.andExpect(jsonPath("$.genreIds[0]").value(missing.toString()));
		assertThat(albumsTitled(title)).isZero();
	}

	@Test
	void hiddenGenreIsRefusedWith422() throws Exception {
		UUID hidden = genre();
		jdbc.sql("UPDATE catalog.genres SET hidden = true WHERE id = :id").param("id", hidden).update();
		create(Role.STAFF, album(uniqueTitle(), "\"genreIds\":[\"" + hidden + "\"],"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.genreIds[0]").value(hidden.toString()));
	}

	@Test
	void repeatedGenreIdIsLinkedOnce() throws Exception {
		UUID rock = genre();
		create(Role.STAFF, album(uniqueTitle(), "\"genreIds\":[\"" + rock + "\",\"" + rock + "\"],"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.genres", hasSize(1)));
	}

	@Test
	void titlesAreStripped() throws Exception {
		String title = uniqueTitle();
		create(Role.STAFF, """
				{"title":"  %s  ","artist":"  Fleetwood Mac ","songs":[{"title":"  Dreams "}]}""".formatted(title))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value(title))
			.andExpect(jsonPath("$.artist").value("Fleetwood Mac"))
			.andExpect(jsonPath("$.songs[0].title").value("Dreams"));
	}

	@Test
	void blankOrMissingFieldsAre400() throws Exception {
		String[] bodies = {
			"{\"title\":\"   \",\"artist\":\"A\",\"songs\":[{\"title\":\"S\"}]}",
			"{\"artist\":\"A\",\"songs\":[{\"title\":\"S\"}]}",
			"{\"title\":\"T\",\"artist\":\"A\"}",
			"{\"title\":\"T\",\"artist\":\"A\",\"songs\":[]}",
			"{\"title\":\"T\",\"artist\":\"A\",\"songs\":[{\"title\":\" \"}]}",
			"{\"title\":\"T\",\"artist\":\"A\",\"songs\":[{\"title\":\"S\",\"durationSeconds\":0}]}",
			"{\"title\":\"" + "x".repeat(201) + "\",\"artist\":\"A\",\"songs\":[{\"title\":\"S\"}]}",
			"{\"title\":\"T\",\"artist\":\"A\",\"releaseYear\":999,\"songs\":[{\"title\":\"S\"}]}",
			"{\"title\":\"T\",\"artist\":\"A\",\"genreIds\":[null],\"songs\":[{\"title\":\"S\"}]}",
		};
		for (String body : bodies) {
			create(Role.STAFF, body)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		}
	}

	@Test
	void releaseYearMayBeNextYearButNotLater() throws Exception {
		int next = Year.now(ZoneOffset.UTC).getValue() + 1;
		create(Role.STAFF, album(uniqueTitle(), "\"releaseYear\":" + next + ","))
			.andExpect(status().isCreated());
		create(Role.STAFF, album(uniqueTitle(), "\"releaseYear\":" + (next + 1) + ","))
			.andExpect(status().isBadRequest())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
	}

	private ResultActions create(Role role, String body) throws Exception {
		return mockMvc.perform(post(ALBUMS)
			.header("Authorization", StaffTokens.bearer(tokens, role))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	/** {@code extra} is spliced in before "songs", e.g. {@code "releaseYear":1977,}. */
	private static String album(String title, String extra) {
		return "{\"title\":\"%s\",\"artist\":\"Fleetwood Mac\",%s\"songs\":[{\"title\":\"Dreams\"}]}"
			.formatted(title, extra);
	}

	private UUID genre() {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO catalog.genres (id, name) VALUES (:id, :name)")
			.param("id", id).param("name", "Genre " + id).update();
		return id;
	}

	private long albumsTitled(String title) {
		return jdbc.sql("SELECT count(*) FROM catalog.albums WHERE title = :title").param("title", title)
			.query(Long.class).single();
	}

	private static String uniqueTitle() {
		return "Rumours " + UUID.randomUUID();
	}
}
