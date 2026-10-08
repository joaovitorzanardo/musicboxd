package com.musicboxd.api.accounts;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/**
 * Missing, unknown, expired, revoked or reused refresh token: one answer, so the response reveals nothing.
 * Clears the cookie so the browser stops sending a dead token. The SPA (MBD-22) sends the person to login.
 */
public class InvalidRefreshTokenException extends ErrorResponseException {

	public static final URI TYPE = URI.create("urn:musicboxd:problem:invalid-refresh-token");

	public InvalidRefreshTokenException() {
		super(HttpStatus.UNAUTHORIZED, problem(), null);
		getHeaders().add(HttpHeaders.SET_COOKIE, RefreshCookies.clear().toString());
	}

	private static ProblemDetail problem() {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Your session has ended; log in again");
		problem.setType(TYPE);
		problem.setTitle("Session expired");
		return problem;
	}
}
