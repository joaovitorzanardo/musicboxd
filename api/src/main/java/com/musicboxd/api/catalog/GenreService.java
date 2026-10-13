package com.musicboxd.api.catalog;

import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.musicboxd.api.db.Constraints;

/** The controlled genre list (AD-13). Genres are renamed or hidden, never deleted (AD-12). */
@Service
class GenreService {

	private final GenreRepository genres;

	GenreService(GenreRepository genres) {
		this.genres = genres;
	}

	@Transactional
	GenreView create(String name) {
		UUID id = UUID.randomUUID();
		try {
			genres.insert(id, name);
		}
		catch (DuplicateKeyException e) {
			throw taken(e);
		}
		return new GenreView(id, name, false);
	}

	@Transactional
	GenreView rename(UUID id, String name) {
		try {
			return genres.rename(id, name).orElseThrow(GenreNotFoundException::new);
		}
		catch (DuplicateKeyException e) {
			throw taken(e);
		}
	}

	/** Hidden genres leave the options for new picks but stay on the albums and profiles that use them. */
	@Transactional
	void setHidden(UUID id, boolean hidden) {
		if (genres.setHidden(id, hidden) == 0) {
			throw new GenreNotFoundException();
		}
	}

	/** The unique index ignores case, so it also catches a rename onto another genre's name in any case. */
	private static RuntimeException taken(DuplicateKeyException e) {
		return Constraints.violated(e, GenreRepository.NAME_UNIQUE) ? new GenreTakenException() : e;
	}
}
