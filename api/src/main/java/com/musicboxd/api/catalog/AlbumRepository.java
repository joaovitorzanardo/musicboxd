package com.musicboxd.api.catalog;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes albums, their songs and genre links. Package-private: other modules will read through a catalog API (AD-1). */
@Repository
class AlbumRepository {

	private final JdbcClient jdbc;

	AlbumRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Returns the database's created_at, so the response matches what is stored. */
	Instant insertAlbum(UUID id, String title, String artist, Integer releaseYear) {
		return jdbc.sql("""
				INSERT INTO catalog.albums (id, title, artist, release_year)
				VALUES (:id, :title, :artist, :releaseYear)
				RETURNING created_at""")
			.param("id", id)
			.param("title", title)
			.param("artist", artist)
			.param("releaseYear", releaseYear, Types.SMALLINT)
			.query((rs, n) -> rs.getObject("created_at", OffsetDateTime.class).toInstant())
			.single();
	}

	void insertSong(UUID id, UUID albumId, int trackNumber, String title, Integer durationSeconds) {
		jdbc.sql("""
				INSERT INTO catalog.songs (id, album_id, track_number, title, duration_seconds)
				VALUES (:id, :albumId, :trackNumber, :title, :durationSeconds)""")
			.param("id", id)
			.param("albumId", albumId)
			.param("trackNumber", trackNumber)
			.param("title", title)
			.param("durationSeconds", durationSeconds, Types.INTEGER)
			.update();
	}

	void linkGenre(UUID albumId, UUID genreId) {
		jdbc.sql("INSERT INTO catalog.album_genres (album_id, genre_id) VALUES (:albumId, :genreId)")
			.param("albumId", albumId)
			.param("genreId", genreId)
			.update();
	}
}
