package com.musicboxd.api.accounts;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** The type lets the SPA (MBD-22) show the message under the email field. */
public class EmailTakenException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:email-taken");

	public EmailTakenException() {
		super(HttpStatus.CONFLICT, problem(), null);
	}

	private static ProblemDetail problem() {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "An account with this email already exists");
		problem.setType(TYPE);
		problem.setTitle("Email taken");
		return problem;
	}
}
