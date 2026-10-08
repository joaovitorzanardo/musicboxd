package com.musicboxd.api.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 401s raised by the security filter chain as RFC 9457 Problem Details. The bearer entry point
 * still sets status and {@code WWW-Authenticate}; the body is generic and never echoes token errors.
 */
final class ProblemDetailsAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private static final String BODY = "{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
			+ "\"detail\":\"Authentication required\"}";

	private final BearerTokenAuthenticationEntryPoint delegate = new BearerTokenAuthenticationEntryPoint();

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		delegate.commence(request, response, authException);
		response.setStatus(HttpStatus.UNAUTHORIZED.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding(StandardCharsets.UTF_8.name());
		response.getWriter().write(BODY);
	}
}
