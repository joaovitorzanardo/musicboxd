package com.musicboxd.api.accounts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** One message for unknown email and wrong password, so login does not reveal which emails exist. */
public class InvalidCredentialsException extends ErrorResponseException {

	public InvalidCredentialsException() {
		super(HttpStatus.UNAUTHORIZED,
				ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid email or password"), null);
	}
}
