package com.musicboxd.api.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 403s raised by the security filter chain (a valid token without the role a route needs, MBD-21) as
 * RFC 9457 Problem Details. The bearer handler still sets {@code WWW-Authenticate}; the body never names the role.
 */
final class ProblemDetailsAccessDeniedHandler implements AccessDeniedHandler {

	private static final String BODY = "{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
			+ "\"detail\":\"Not allowed\"}";

	private final BearerTokenAccessDeniedHandler delegate = new BearerTokenAccessDeniedHandler();

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException, ServletException {
		delegate.handle(request, response, accessDeniedException);
		response.setStatus(HttpStatus.FORBIDDEN.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(BODY);
	}
}
