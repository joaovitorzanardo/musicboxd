package com.musicboxd.api.ratelimit;

import java.time.Duration;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

/** Rendered by Spring MVC as a 429 problem detail with a {@code Retry-After} in whole seconds. */
public class RateLimitExceededException extends ErrorResponseException {

	private final HttpHeaders headers = new HttpHeaders();

	public RateLimitExceededException(Duration retryAfter) {
		super(HttpStatus.TOO_MANY_REQUESTS,
			ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded. Retry later."),
			null);
		long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
		headers.set(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
	}

	@Override
	public HttpHeaders getHeaders() {
		return headers;
	}
}
