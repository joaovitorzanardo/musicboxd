package com.musicboxd.api.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.musicboxd.api.TestcontainersConfiguration;

/** The HTTP edge validates first (GenreAdminTest, AlbumAdminTest); these constraints are the backstop. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CatalogSchemaTest {

	@Autowired
	private JdbcClient jdbc;

	@Test
	void albumWithSongsIsStoredVisibleByDefault() {
		UUID album = insertAlbum("Rumours");
		insertSong(album, 1);
		insertSong(album, 2);
		assertThat(jdbc.sql("SELECT hidden FROM catalog.albums WHERE id = :id").param("id", album)
			.query(Boolean.class).single()).isFalse();
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.songs WHERE album_id = :id").param("id", album)
			.query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void trackNumbersAreUniquePerAlbum() {
		UUID album = insertAlbum("Rumours");
		insertSong(album, 1);
		assertThatThrownBy(() -> insertSong(album, 1)).isInstanceOf(DuplicateKeyException.class);
		// Another album may reuse the number.
		insertSong(insertAlbum("Tusk"), 1);
	}

	@Test
	void songNeedsAnExistingAlbum() {
		assertThatThrownBy(() -> insertSong(UUID.randomUUID(), 1))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void deletingAnAlbumNeverCascadesToItsSongs() {
		// AD-12: nothing deletes catalog rows; if something tried, the songs would block it.
		UUID album = insertAlbum("Rumours");
		insertSong(album, 1);
		assertThatThrownBy(() -> jdbc.sql("DELETE FROM catalog.albums WHERE id = :id").param("id", album).update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void genreNamesAreUniqueIgnoringCase() {
		String name = "Rock " + UUID.randomUUID();
		insertGenre(name);
		assertThatThrownBy(() -> insertGenre(name.toUpperCase())).isInstanceOf(DuplicateKeyException.class);
	}

	@Test
	void albumGenreLinkNeedsBothSides() {
		UUID album = insertAlbum("Rumours");
		UUID genre = insertGenre("Rock " + UUID.randomUUID());
		jdbc.sql("INSERT INTO catalog.album_genres (album_id, genre_id) VALUES (:a, :g)")
			.param("a", album).param("g", genre).update();
		assertThatThrownBy(() -> jdbc.sql("INSERT INTO catalog.album_genres (album_id, genre_id) VALUES (:a, :g)")
			.param("a", album).param("g", UUID.randomUUID()).update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void blankTitlesAndImplausibleValuesAreRejected() {
		assertThatThrownBy(() -> insertAlbum("   ")).isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO catalog.albums (id, title, artist, release_year) VALUES (:id, 'X', 'Y', 999)""")
			.param("id", UUID.randomUUID()).update()).isInstanceOf(DataIntegrityViolationException.class);
		UUID album = insertAlbum("Rumours");
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO catalog.songs (id, album_id, track_number, title, duration_seconds)
				VALUES (:id, :album, 1, 'Dreams', 0)""")
			.param("id", UUID.randomUUID()).param("album", album).update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private UUID insertAlbum(String title) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO catalog.albums (id, title, artist) VALUES (:id, :title, 'Fleetwood Mac')")
			.param("id", id).param("title", title).update();
		return id;
	}

	private void insertSong(UUID album, int trackNumber) {
		jdbc.sql("INSERT INTO catalog.songs (id, album_id, track_number, title) VALUES (:id, :album, :n, 'Dreams')")
			.param("id", UUID.randomUUID()).param("album", album).param("n", trackNumber).update();
	}

	private UUID insertGenre(String name) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO catalog.genres (id, name) VALUES (:id, :name)").param("id", id).param("name", name).update();
		return id;
	}
}
