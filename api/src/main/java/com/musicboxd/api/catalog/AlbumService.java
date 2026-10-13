package com.musicboxd.api.catalog;

import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

import com.musicboxd.api.catalog.AlbumAdminController.CreateAlbumRequest;
import com.musicboxd.api.catalog.AlbumAdminController.TrackRequest;
import com.musicboxd.api.catalog.AlbumAdminController.UpdateAlbumRequest;

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
		List<GenreView> picked = requireAll(request.genreIds(), genres.findVisible(request.genreIds()));

		UUID albumId = UUID.randomUUID();
		Instant createdAt = albums.insertAlbum(albumId, request.title(), request.artist(), request.releaseYear());

		var songs = new ArrayList<SongView>();
		for (int i = 0; i < request.songs().size(); i++) {
			var song = request.songs().get(i);
			var view = new SongView(UUID.randomUUID(), i + 1, song.title(), song.durationSeconds(), false);
			albums.insertSong(view.id(), albumId, view.trackNumber(), view.title(), view.durationSeconds(), false);
			songs.add(view);
		}
		picked.forEach(genre -> albums.linkGenre(albumId, genre.id()));

		return new AlbumView(albumId, request.title(), request.artist(), request.releaseYear(), false, createdAt,
				List.copyOf(songs), picked);
	}

	/**
	 * One transaction, under a row lock on the album: every check runs before any write, then the album,
	 * its tracklist (numbered by position) and its genre links are rewritten.
	 */
	@Transactional
	AlbumView update(UUID id, UpdateAlbumRequest request) {
		requirePlausibleYear(request.releaseYear());
		if (!albums.lockAlbum(id)) {
			throw new AlbumNotFoundException();
		}
		List<GenreView> picked = requireAll(request.genreIds(), genres.findSelectableFor(id, request.genreIds()));
		requireSameSongs(id, request.songs());

		albums.updateAlbum(id, request.title(), request.artist(), request.releaseYear(), request.hidden());
		albums.deferTrackNumberCheck();
		for (int i = 0; i < request.songs().size(); i++) {
			TrackRequest track = request.songs().get(i);
			if (track.id() == null) {
				albums.insertSong(UUID.randomUUID(), id, i + 1, track.title(), track.durationSeconds(), track.hidden());
			}
			else {
				albums.updateSong(track.id(), id, i + 1, track.title(), track.durationSeconds(), track.hidden());
			}
		}
		albums.replaceGenres(id, picked.stream().map(GenreView::id).toList());
		return view(id, true);
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

	@Transactional
	void setAlbumHidden(UUID id, boolean hidden) {
		if (albums.setAlbumHidden(id, hidden) == 0) {
			throw new AlbumNotFoundException();
		}
	}

	@Transactional
	void setSongHidden(UUID id, boolean hidden) {
		if (albums.setSongHidden(id, hidden) == 0) {
			throw new SongNotFoundException();
		}
	}

	private AlbumView view(UUID id, boolean withHiddenSongs) {
		var album = albums.findAlbum(id).orElseThrow(AlbumNotFoundException::new);
		return new AlbumView(album.id(), album.title(), album.artist(), album.releaseYear(), album.hidden(),
				album.createdAt(), albums.findSongs(id, withHiddenSongs), albums.findGenres(id));
	}

	/** Every existing song exactly once: nothing is deleted or moved (AD-12). */
	private void requireSameSongs(UUID albumId, List<TrackRequest> tracks) {
		Set<UUID> existing = new LinkedHashSet<>(albums.songIds(albumId));
		Set<UUID> sent = new LinkedHashSet<>();
		Set<UUID> repeated = new LinkedHashSet<>();
		for (TrackRequest track : tracks) {
			if (track.id() != null && !sent.add(track.id())) {
				repeated.add(track.id());
			}
		}
		List<UUID> unknown = sent.stream().filter(songId -> !existing.contains(songId)).toList();
		List<UUID> missing = existing.stream().filter(songId -> !sent.contains(songId)).toList();
		if (!missing.isEmpty() || !unknown.isEmpty() || !repeated.isEmpty()) {
			throw new TracklistMismatchException(missing, unknown, List.copyOf(repeated));
		}
	}

	/** Checked before any write. Unknown and unselectable ids are refused together, in request order. */
	private static List<GenreView> requireAll(List<UUID> ids, Map<UUID, GenreView> found) {
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
