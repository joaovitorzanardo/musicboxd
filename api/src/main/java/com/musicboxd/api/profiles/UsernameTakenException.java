package com.musicboxd.api.profiles;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The type lets the SPA (MBD-22) show the message under the username field. */
public class UsernameTakenException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:username-taken");

	public UsernameTakenException() {
		super(HttpStatus.CONFLICT, problem(), null);
	}

	private static ProblemDetail problem() {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Username is already taken");
		problem.setType(TYPE);
		problem.setTitle("Username taken");
		return problem;
	}
}
