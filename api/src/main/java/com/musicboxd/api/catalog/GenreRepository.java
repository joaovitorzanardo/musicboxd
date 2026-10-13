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

	/** For an edit: visible genres, plus hidden ones this album already carries (hiding keeps existing uses, UX "Lista de generos"). */
	Map<UUID, GenreView> findSelectableFor(UUID albumId, Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("""
				SELECT g.id, g.name, g.hidden FROM catalog.genres g
				WHERE g.id IN (:ids)
				  AND (NOT g.hidden OR EXISTS (
				      SELECT 1 FROM catalog.album_genres ag WHERE ag.album_id = :albumId AND ag.genre_id = g.id))""")
			.param("ids", ids)
			.param("albumId", albumId)
			.query(GenreView.class)
			.list()
			.stream()
			.collect(Collectors.toMap(GenreView::id, Function.identity()));
	}
}
