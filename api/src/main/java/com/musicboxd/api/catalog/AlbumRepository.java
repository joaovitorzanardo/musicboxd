package com.musicboxd.api.catalog;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes albums, their songs and genre links. Package-private: other modules will read through a catalog API (AD-1). */
@Repository
class AlbumRepository {

	private final JdbcClient jdbc;

	AlbumRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Album columns only; songs and genres are read separately. */
	record AlbumRow(UUID id, String title, String artist, Integer releaseYear, boolean hidden, Instant createdAt) {
	}

	Optional<AlbumRow> findAlbum(UUID id) {
		return jdbc.sql("SELECT id, title, artist, release_year, hidden, created_at FROM catalog.albums WHERE id = :id")
			.param("id", id)
			.query((rs, n) -> new AlbumRow(rs.getObject("id", UUID.class), rs.getString("title"),
					rs.getString("artist"), rs.getObject("release_year", Integer.class), rs.getBoolean("hidden"),
					rs.getObject("created_at", OffsetDateTime.class).toInstant()))
			.optional();
	}

	/** In track order. The public album page leaves hidden songs out; the admin editor shows them. */
	List<SongView> findSongs(UUID albumId, boolean withHidden) {
		return jdbc.sql("""
				SELECT id, track_number, title, duration_seconds, hidden FROM catalog.songs
				WHERE album_id = :albumId AND (:withHidden OR NOT hidden)
				ORDER BY track_number""")
			.param("albumId", albumId)
			.param("withHidden", withHidden)
			.query(SongView.class)
			.list();
	}

	/** album_genres keeps no order, so reads list genres by name. Hidden genres stay on the albums that use them. */
	List<GenreView> findGenres(UUID albumId) {
		return jdbc.sql("""
				SELECT g.id, g.name, g.hidden
				FROM catalog.album_genres ag JOIN catalog.genres g ON g.id = ag.genre_id
				WHERE ag.album_id = :albumId
				ORDER BY lower(g.name), g.id""")
			.param("albumId", albumId)
			.query(GenreView.class)
			.list();
	}

	Optional<SongPageView> findSongPage(UUID id) {
		return jdbc.sql("""
				SELECT s.id, s.track_number, s.title, s.duration_seconds, s.hidden,
				       a.id AS album_id, a.title AS album_title, a.artist, a.release_year, a.hidden AS album_hidden
				FROM catalog.songs s JOIN catalog.albums a ON a.id = s.album_id
				WHERE s.id = :id""")
			.param("id", id)
			.query((rs, n) -> new SongPageView(rs.getObject("id", UUID.class), rs.getInt("track_number"),
					rs.getString("title"), rs.getObject("duration_seconds", Integer.class), rs.getBoolean("hidden"),
					new AlbumSummary(rs.getObject("album_id", UUID.class), rs.getString("album_title"),
							rs.getString("artist"), rs.getObject("release_year", Integer.class),
							rs.getBoolean("album_hidden"))))
			.optional();
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
