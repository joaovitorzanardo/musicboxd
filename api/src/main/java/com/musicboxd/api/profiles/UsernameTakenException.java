package com.musicboxd.api.profiles;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class UsernameTakenException extends ErrorResponseException {

	public UsernameTakenException() {
		super(HttpStatus.CONFLICT,
				ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "Username is already taken"), null);
	}
}
