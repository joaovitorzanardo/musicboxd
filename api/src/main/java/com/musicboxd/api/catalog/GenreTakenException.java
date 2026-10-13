package com.musicboxd.api.catalog;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The type lets the admin SPA (MBD-53) show the message under the genre name field. */
public class GenreTakenException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:genre-taken");

	public GenreTakenException() {
		super(HttpStatus.CONFLICT, problem(), null);
	}

	private static ProblemDetail problem() {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "A genre with this name already exists");
		problem.setType(TYPE);
		problem.setTitle("Genre taken");
		return problem;
	}
}
