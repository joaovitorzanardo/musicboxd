package com.musicboxd.api.ratelimit;

import java.time.Duration;

/**
 * {@code capacity} requests per {@code refillPeriod}, refilled continuously (token bucket).
 */
public record RateLimitPolicy(int capacity, Duration refillPeriod) {

	public RateLimitPolicy {
		if (capacity < 1) {
			throw new IllegalArgumentException("capacity must be >= 1");
		}
		if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative()) {
			throw new IllegalArgumentException("refillPeriod must be positive");
		}
	}
}
