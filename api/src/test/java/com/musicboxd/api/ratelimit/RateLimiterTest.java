package com.musicboxd.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Token-bucket semantics of the per-user limiter (AD-10), driven by a hand-advanced
 * nanosecond source so refill is tested without sleeping.
 */
class RateLimiterTest {

	/** Monotonic time source the tests advance by hand. */
	static class FakeNanoTime {

		private long nanos;

		void advance(Duration d) {
			nanos += d.toNanos();
		}

		long read() {
			return nanos;
		}
	}

	private final FakeNanoTime clock = new FakeNanoTime();

	private final Map<String, RateLimitPolicy> policies = Map.of(
		"a", new RateLimitPolicy(3, Duration.ofMinutes(1)),
		"b", new RateLimitPolicy(1, Duration.ofMinutes(1)));

	private RateLimiter limiter(int maxTrackedKeys) {
		return new RateLimiter(policies, maxTrackedKeys, clock::read);
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
	void fullPolicyKeepsServingNewCallersInsteadOfRejectingThem() {
		var l = limiter(2);
		for (int i = 0; i < 3; i++) {
			l.tryAcquire("a", "k1"); // k1 exhausted
		}
		// Far more distinct callers than tracked slots: none of them is turned away.
		for (int i = 0; i < 100; i++) {
			assertThat(l.tryAcquire("a", "new-" + i).allowed()).isTrue();
		}
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
