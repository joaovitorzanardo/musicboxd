package com.musicboxd.api.accounts;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Right password, unverified email (AD-8). The type lets the SPA (MBD-22) show "Verifique seu email". */
public class EmailNotVerifiedException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:email-not-verified");

	public EmailNotVerifiedException() {
		super(HttpStatus.FORBIDDEN, problem(), null);
	}

	private static ProblemDetail problem() {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
				"Confirm your email address with the link we sent before logging in");
		problem.setType(TYPE);
		problem.setTitle("Email not verified");
		return problem;
	}
}
