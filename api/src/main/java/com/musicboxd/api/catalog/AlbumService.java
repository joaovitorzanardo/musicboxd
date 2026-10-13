package com.musicboxd.api.catalog;

import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

import com.musicboxd.api.catalog.AlbumAdminController.CreateAlbumRequest;

@Service
class AlbumService {

	private final AlbumRepository albums;
	private final GenreRepository genres;
	private final Clock clock;

	AlbumService(AlbumRepository albums, GenreRepository genres, Clock clock) {
		this.albums = albums;
		this.genres = genres;
		this.clock = clock;
	}

	/** One transaction: the album, its tracklist (numbered by position) and its genres, or nothing. */
	@Transactional
	AlbumView create(CreateAlbumRequest request) {
		requirePlausibleYear(request.releaseYear());
		List<GenreView> picked = visibleGenres(request.genreIds());

		UUID albumId = UUID.randomUUID();
		Instant createdAt = albums.insertAlbum(albumId, request.title(), request.artist(), request.releaseYear());

		var songs = new ArrayList<SongView>();
		for (int i = 0; i < request.songs().size(); i++) {
			var song = request.songs().get(i);
			var view = new SongView(UUID.randomUUID(), i + 1, song.title(), song.durationSeconds(), false);
			albums.insertSong(view.id(), albumId, view.trackNumber(), view.title(), view.durationSeconds());
			songs.add(view);
		}
		picked.forEach(genre -> albums.linkGenre(albumId, genre.id()));

		return new AlbumView(albumId, request.title(), request.artist(), request.releaseYear(), false, createdAt,
				List.copyOf(songs), picked);
	}

	/** The editor's view: every song, hidden ones flagged. */
	@Transactional(readOnly = true)
	AlbumView adminView(UUID id) {
		return view(id, true);
	}

	/** The Album page: visible songs only. A hidden Album still resolves (AD-12). */
	@Transactional(readOnly = true)
	AlbumView publicView(UUID id) {
		return view(id, false);
	}

	@Transactional(readOnly = true)
	SongPageView songPage(UUID id) {
		return albums.findSongPage(id).orElseThrow(SongNotFoundException::new);
	}

	private AlbumView view(UUID id, boolean withHiddenSongs) {
		var album = albums.findAlbum(id).orElseThrow(AlbumNotFoundException::new);
		return new AlbumView(album.id(), album.title(), album.artist(), album.releaseYear(), album.hidden(),
				album.createdAt(), albums.findSongs(id, withHiddenSongs), albums.findGenres(id));
	}

	/** Checked before any insert. Unknown and hidden ids are refused together. */
	private List<GenreView> visibleGenres(List<UUID> ids) {
		Map<UUID, GenreView> found = genres.findVisible(ids);
		List<UUID> missing = ids.stream().filter(id -> !found.containsKey(id)).toList();
		if (!missing.isEmpty()) {
			throw new UnknownGenreException(missing);
		}
		return ids.stream().map(found::get).toList();
	}

	/** Upcoming releases may be entered ahead of time, up to next year (UTC). */
	private void requirePlausibleYear(Integer year) {
		int latest = Year.now(clock).getValue() + 1;
		if (year != null && year > latest) {
			throw new ErrorResponseException(HttpStatus.BAD_REQUEST,
					ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "releaseYear must not be after " + latest),
					null);
		}
	}
}
