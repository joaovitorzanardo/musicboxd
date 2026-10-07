package com.musicboxd.api.ratelimit;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Named per-user limit policies, e.g. {@code musicboxd.rate-limit.policies.demo.capacity=5}
 * and {@code musicboxd.rate-limit.policies.demo.refill-period=1m}.
 */
@ConfigurationProperties("musicboxd.rate-limit")
public record RateLimitProperties(Map<String, RateLimitPolicy> policies) {

	public RateLimitProperties {
		policies = policies == null ? Map.of() : Map.copyOf(policies);
	}
}
