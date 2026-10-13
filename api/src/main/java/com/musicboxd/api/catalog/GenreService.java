package com.musicboxd.api.catalog;

import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.ErrorResponseException;

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

	/** Renames, hides or shows again; a null argument is left as it is. Hidden genres keep their album links. */
	@Transactional
	GenreView edit(UUID id, String name, Boolean hidden) {
		if (name == null && hidden == null) {
			throw new ErrorResponseException(HttpStatus.BAD_REQUEST,
					ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Send name, hidden, or both"), null);
		}
		try {
			return genres.update(id, name, hidden).orElseThrow(GenreNotFoundException::new);
		}
		catch (DuplicateKeyException e) {
			throw taken(e);
		}
	}

	/** The unique index ignores case, so it also catches a rename onto another genre's name in any case. */
	private static RuntimeException taken(DuplicateKeyException e) {
		return Constraints.violated(e, GenreRepository.NAME_UNIQUE) ? new GenreTakenException() : e;
	}
}
