package com.musicboxd.api.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class AlbumNotFoundException extends ErrorResponseException {

	public AlbumNotFoundException() {
		super(HttpStatus.NOT_FOUND, ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Album not found"), null);
	}
}
