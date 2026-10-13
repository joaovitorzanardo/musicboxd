package com.musicboxd.api.catalog;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** A picked genre that does not exist or is hidden; {@code genreIds} names them so the SPA can drop them. */
public class UnknownGenreException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:unknown-genre");

	public UnknownGenreException(List<UUID> genreIds) {
		super(HttpStatus.UNPROCESSABLE_CONTENT, problem(genreIds), null);
	}

	private static ProblemDetail problem(List<UUID> genreIds) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
				"Some genres do not exist or are hidden");
		problem.setType(TYPE);
		problem.setTitle("Unknown genre");
		problem.setProperty("genreIds", genreIds);
		return problem;
	}
}
