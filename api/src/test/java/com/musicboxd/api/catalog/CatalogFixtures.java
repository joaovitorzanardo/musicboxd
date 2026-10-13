package com.musicboxd.api.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

/** Catalog rows for tests that start from an existing Album. Tests share one database: names are unique. */
final class CatalogFixtures {

	private CatalogFixtures() {
	}

	/** Created through the real Staff endpoint, songs numbered 1..n; returns the 201 body. */
	static String createAlbum(MockMvc mockMvc, TokenService tokens, List<UUID> genreIds, String... songTitles)
			throws Exception {
		String genres = genreIds.stream().map(id -> "\"" + id + "\"").collect(Collectors.joining(","));
		String songs = Stream.of(songTitles).map(t -> "{\"title\":\"" + t + "\"}").collect(Collectors.joining(","));
		return mockMvc.perform(post("/api/v1/admin/catalog/albums")
				.header("Authorization", StaffTokens.bearer(tokens, Role.STAFF))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"title":"Rumours %s","artist":"Fleetwood Mac","releaseYear":1977,"genreIds":[%s],"songs":[%s]}"""
					.formatted(UUID.randomUUID(), genres, songs)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString();
	}

	static UUID uuidAt(String json, String path) {
		return UUID.fromString(JsonPath.read(json, path));
	}

	/** {@code prefix} lets a test control name order, e.g. "Ambient" sorts before "Zydeco". */
	static UUID genre(JdbcClient jdbc, String prefix, boolean hidden) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO catalog.genres (id, name, hidden) VALUES (:id, :name, :hidden)")
			.param("id", id)
			.param("name", prefix + " " + id)
			.param("hidden", hidden)
			.update();
		return id;
	}
}
