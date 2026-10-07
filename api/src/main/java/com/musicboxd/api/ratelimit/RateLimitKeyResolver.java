package com.musicboxd.api.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Identifies the caller a {@link RateLimited} endpoint counts against. Implementations are
 * beans, selected by name in {@link RateLimited#keyResolver()}.
 */
public interface RateLimitKeyResolver {

	/** Stable identity of the caller; must not be derivable from client-forgeable input. */
	String resolve(HttpServletRequest request);
}
