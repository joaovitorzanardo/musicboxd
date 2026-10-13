package com.musicboxd.api.catalog;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class GenreRepository {

	static final String NAME_UNIQUE = "genres_name_key";

	private final JdbcClient jdbc;

	GenreRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(UUID id, String name) {
		jdbc.sql("INSERT INTO catalog.genres (id, name) VALUES (:id, :name)")
			.param("id", id)
			.param("name", name)
			.update();
	}

	/** Hidden genres are left out: they are no longer selectable (UX, Lista de generos). */
	Map<UUID, GenreView> findVisible(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("SELECT id, name, hidden FROM catalog.genres WHERE id IN (:ids) AND NOT hidden")
			.param("ids", ids)
			.query(GenreView.class)
			.list()
			.stream()
			.collect(Collectors.toMap(GenreView::id, Function.identity()));
	}
}
