package com.musicboxd.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** The programmatic form of {@link RateLimited}, for keys only the controller can see (MBD-23). */
class RateLimitsTest {

	private final RateLimits limits = new RateLimits(
			new RateLimiter(Map.of("p", new RateLimitPolicy(2, Duration.ofHours(1))), 100));

	@Test
	void allowsUpToCapacityThenThrowsA429WithRetryAfter() {
		limits.check("p", "k");
		limits.check("p", "k");

		assertThatThrownBy(() -> limits.check("p", "k"))
			.isInstanceOfSatisfying(RateLimitExceededException.class, e -> {
				assertThat(e.getStatusCode().value()).isEqualTo(429);
				// 2 per hour, refilled continuously: the next token is 30 minutes away.
				assertThat(e.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1800");
			});
	}

	@Test
	void keysHaveIndependentBuckets() {
		limits.check("p", "k");
		limits.check("p", "k");

		limits.check("p", "other");
	}

	@Test
	void anUnknownPolicyFailsLoudly() {
		assertThatThrownBy(() -> limits.check("nope", "k")).isInstanceOf(IllegalArgumentException.class);
	}
}
