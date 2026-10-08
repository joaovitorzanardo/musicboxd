package com.musicboxd.api.accounts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Unknown, replaced or expired link: one message, so a link reveals nothing about accounts. */
public class InvalidVerificationTokenException extends ErrorResponseException {

	public InvalidVerificationTokenException() {
		super(HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"This verification link is invalid or expired; request a new one"), null);
	}
}
