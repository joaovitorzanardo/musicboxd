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
	void fullPolicyEvictsLeastRecentlyUsedKeyInsteadOfRejectingNewCallers() {
		var l = limiter(2);
		l.tryAcquire("a", "k1");
		clock.advance(Duration.ofSeconds(1));
		for (int i = 0; i < 3; i++) {
			l.tryAcquire("a", "k2"); // k2 exhausted and most recently used
		}
		// Full of live keys: a new caller is still served (k1, the LRU key, is evicted)...
		assertThat(l.tryAcquire("a", "k3").allowed()).isTrue();
		// ...and the recently used, exhausted k2 keeps its bucket.
		assertThat(l.tryAcquire("a", "k2").allowed()).isFalse();
	}

	@Test
	void keyFloodOnOnePolicyDoesNotTouchAnotherPolicysBuckets() {
		var l = limiter(2);
		l.tryAcquire("b", "victim"); // policy b: capacity 1, now exhausted
		for (int i = 0; i < 100; i++) {
			assertThat(l.tryAcquire("a", "flood-" + i).allowed()).isTrue();
		}
		// The flood on policy a neither rejected anyone nor evicted b's bucket.
		assertThat(l.tryAcquire("b", "victim").allowed()).isFalse();
	}
}
