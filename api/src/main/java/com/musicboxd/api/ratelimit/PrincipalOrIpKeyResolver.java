package com.musicboxd.api.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Default caller identity: the authenticated principal when present (once AD-8 adds
 * Spring Security), otherwise the client IP.
 */
public class PrincipalOrIpKeyResolver implements RateLimitKeyResolver {

	@Override
	public String resolve(HttpServletRequest request) {
		var principal = request.getUserPrincipal();
		if (principal != null) {
			return "user:" + principal.getName();
		}
		// Not the raw X-Forwarded-For header: with forward-headers-strategy=native the
		// container has already resolved the client IP nginx forwarded into remoteAddr.
		return "ip:" + request.getRemoteAddr();
	}
}
