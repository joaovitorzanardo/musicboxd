package com.musicboxd.api.ratelimit;

/**
 * The programmatic form of {@link RateLimited}: for a caller key that only the controller can
 * see, such as an email in the request body (MBD-23). Shares the interceptor's buckets and 429.
 */
public class RateLimits {

	private final RateLimiter limiter;

	public RateLimits(RateLimiter limiter) {
		this.limiter = limiter;
	}

	/**
	 * Takes one request from {@code key}'s bucket under {@code policy}, configured at
	 * {@code musicboxd.rate-limit.policies.<policy>}. Past the limit it throws
	 * {@link RateLimitExceededException}, which is answered 429 with {@code Retry-After}.
	 */
	public void check(String policy, String key) {
		var decision = limiter.tryAcquire(policy, key);
		if (!decision.allowed()) {
			throw new RateLimitExceededException(decision.retryAfter());
		}
	}
}
