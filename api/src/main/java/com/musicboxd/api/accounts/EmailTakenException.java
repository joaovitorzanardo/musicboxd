package com.musicboxd.api.accounts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class EmailTakenException extends ErrorResponseException {

	public EmailTakenException() {
		super(HttpStatus.CONFLICT,
				ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "An account with this email already exists"), null);
	}
}
