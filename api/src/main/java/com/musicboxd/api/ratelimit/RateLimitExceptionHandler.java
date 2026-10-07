package com.musicboxd.api.ratelimit;

import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Renders {@link RateLimitExceededException} as a JSON problem detail. Without it the
 * exception ends in {@code sendError}, which keeps the status and headers but loses the body.
 */
@RestControllerAdvice
public class RateLimitExceptionHandler {

	@ExceptionHandler(RateLimitExceededException.class)
	ResponseEntity<ProblemDetail> handle(RateLimitExceededException e) {
		return ResponseEntity.status(e.getStatusCode()).headers(e.getHeaders()).body(e.getBody());
	}
}
