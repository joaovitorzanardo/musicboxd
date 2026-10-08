package com.musicboxd.api.accounts;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class PasswordTooLongException extends ErrorResponseException {

	public PasswordTooLongException() {
		super(HttpStatus.BAD_REQUEST,
				ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Password must be at most " + AccountService.MAX_PASSWORD_BYTES + " bytes"), null);
	}
}
