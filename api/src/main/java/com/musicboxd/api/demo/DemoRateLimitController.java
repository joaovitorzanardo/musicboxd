package com.musicboxd.api.demo;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.musicboxd.api.ratelimit.RateLimitKeyResolver;
import com.musicboxd.api.ratelimit.RateLimited;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Proves the per-user limiter end to end (MBD-13). The caller is a self-declared
 * {@code X-Demo-User} header because accounts (AD-8) do not exist yet; that header is
 * forgeable, which is acceptable for a demo and is why real endpoints use the default
 * {@code principalOrIp} resolver. Delete this controller once a real endpoint carries
 * {@link RateLimited}.
 */
@RestController
public class DemoRateLimitController {

	@GetMapping("/api/v1/demo/rate-limited")
	@RateLimited(policy = "demo", keyResolver = "demoUser")
	@ApiResponse(responseCode = "200", description = "Within the caller's limit")
	@ApiResponse(responseCode = "429", description = "Per-user limit exceeded; see Retry-After")
	public Map<String, String> rateLimited() {
		return Map.of("status", "ok");
	}

	@Component("demoUser")
	static class DemoUserKeyResolver implements RateLimitKeyResolver {

		@Override
		public String resolve(HttpServletRequest request) {
			String user = request.getHeader("X-Demo-User");
			if (user == null || user.isBlank()) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Demo-User header is required");
			}
			return "demo:" + user;
		}
	}
}
