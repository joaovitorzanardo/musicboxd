package com.musicboxd.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Token-bucket semantics of the per-user limiter (AD-10), driven by a hand-advanced
 * clock so refill is tested without sleeping.
 */
class RateLimiterTest {

	/** Mutable clock so tests control refill without sleeping. */
	static class TestClock extends Clock {

		private Instant now = Instant.parse("2026-01-01T00:00:00Z");

		void advance(Duration d) {
			now = now.plus(d);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private final TestClock clock = new TestClock();

	private final Map<String, RateLimitPolicy> policies = Map.of(
		"a", new RateLimitPolicy(3, Duration.ofMinutes(1)),
		"b", new RateLimitPolicy(1, Duration.ofMinutes(1)));

	private RateLimiter limiter(int maxTrackedKeys) {
		return new RateLimiter(policies, clock, maxTrackedKeys);
	}

	@Test
	void allowsUpToCapacityThenRejectsWithRetryAfter() {
		var l = limiter(100);
		for (int i = 0; i < 3; i++) {
			assertThat(l.tryAcquire("a", "u1").allowed()).isTrue();
		}
		var denied = l.tryAcquire("a", "u1");
		assertThat(denied.allowed()).isFalse();
		assertThat(denied.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1));
	}

	@Test
	void refillsOverTime() {
		var l = limiter(100);
		for (int i = 0; i < 3; i++) {
			l.tryAcquire("a", "u1");
		}
		assertThat(l.tryAcquire("a", "u1").allowed()).isFalse();
		clock.advance(Duration.ofSeconds(20)); // 3 per minute = 1 token per 20s
		assertThat(l.tryAcquire("a", "u1").allowed()).isTrue();
		assertThat(l.tryAcquire("a", "u1").allowed()).isFalse();
	}

	@Test
	void usersAndPoliciesAreIsolated() {
		var l = limiter(100);
		for (int i = 0; i < 3; i++) {
			l.tryAcquire("a", "u1");
		}
		assertThat(l.tryAcquire("a", "u1").allowed()).isFalse();
		assertThat(l.tryAcquire("a", "u2").allowed()).isTrue(); // other user
		assertThat(l.tryAcquire("b", "u1").allowed()).isTrue(); // other policy
	}

	@Test
	void unknownPolicyFailsLoudly() {
		assertThatThrownBy(() -> limiter(100).tryAcquire("nope", "u1"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Unknown rate limit policy: nope");
	}

	@Test
	void idleKeysAreEvictedAndLiveKeysFailClosedWhenFull() {
		var l = limiter(2);
		assertThat(l.tryAcquire("a", "k1").allowed()).isTrue();
		assertThat(l.tryAcquire("a", "k2").allowed()).isTrue();
		// Map full of live (recently used) keys: a new key is rejected, not admitted.
		assertThat(l.tryAcquire("a", "k3").allowed()).isFalse();
		// After a full refill period k1/k2 are idle and get swept, so k3 fits.
		clock.advance(Duration.ofMinutes(2));
		assertThat(l.tryAcquire("a", "k3").allowed()).isTrue();
	}
}
