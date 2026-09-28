package com.musicboxd.api.health;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Proves the API is reachable end to end (SPA -&gt; nginx -&gt; api) under the
 * versioned {@code /api/v1} contract (AD-7). Deliberately not Spring Boot
 * Actuator's {@code /actuator/health}: that would introduce a second,
 * unversioned endpoint namespace this early.
 */
@RestController
public class HealthController {

	@GetMapping("/api/v1/health")
	public Map<String, String> health() {
		return Map.of("status", "ok");
	}
}
