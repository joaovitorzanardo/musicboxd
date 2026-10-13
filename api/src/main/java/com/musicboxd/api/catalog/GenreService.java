package com.musicboxd.api.catalog;

import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.musicboxd.api.db.Constraints;

/** The controlled genre list (AD-13). Rename and hide arrive with MBD-30. */
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
			if (Constraints.violated(e, GenreRepository.NAME_UNIQUE)) {
				throw new GenreTakenException();
			}
			throw e;
		}
		return new GenreView(id, name, false);
	}
}
