package com.musicboxd.api.ratelimit;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;

/**
 * Bucket4j token bucket per (policy, key), held in memory. Single-host by design (AD-11):
 * no shared store, so state resets on restart. Each policy has its own Caffeine cache,
 * bounded at {@code maxTrackedKeys} and dropping buckets idle for a full refill period
 * (they would be full again anyway). A key flood never locks out new callers and never
 * touches another policy's buckets; at worst an evicted caller gets a fresh bucket.
 */
public class RateLimiter {

	public record Decision(boolean allowed, Duration retryAfter) {
	}

	private final Map<String, RateLimitPolicy> policies;
	private final Map<String, Cache<String, Bucket>> bucketsByPolicy = new HashMap<>();
	private final TimeMeter timeMeter;

	public RateLimiter(Map<String, RateLimitPolicy> policies, int maxTrackedKeys) {
		this(policies, maxTrackedKeys, System::nanoTime);
	}

	/** {@code nanoTime} must be monotonic, like {@link System#nanoTime()}; tests pass a fake. */
	RateLimiter(Map<String, RateLimitPolicy> policies, int maxTrackedKeys, LongSupplier nanoTime) {
		this.policies = Map.copyOf(policies);
		this.timeMeter = new TimeMeter() {
			@Override
			public long currentTimeNanos() {
				return nanoTime.getAsLong();
			}

			@Override
			public boolean isWallClockBased() {
				return false;
			}
		};
		this.policies.forEach((name, policy) -> bucketsByPolicy.put(name, Caffeine.newBuilder()
			.maximumSize(maxTrackedKeys)
			.expireAfterAccess(policy.refillPeriod())
			.ticker(nanoTime::getAsLong)
			.build()));
	}

	public Decision tryAcquire(String policyName, String key) {
		RateLimitPolicy policy = policies.get(policyName);
		if (policy == null) {
			throw new IllegalArgumentException("Unknown rate limit policy: " + policyName);
		}
		Bucket bucket = bucketsByPolicy.get(policyName).get(key, k -> newBucket(policy));
		var probe = bucket.tryConsumeAndReturnRemaining(1);
		return probe.isConsumed()
			? new Decision(true, Duration.ZERO)
			: new Decision(false, Duration.ofNanos(probe.getNanosToWaitForRefill()));
	}

	private Bucket newBucket(RateLimitPolicy policy) {
		return Bucket.builder()
			// Greedy refill: tokens trickle back continuously, not all at once per period.
			.addLimit(limit -> limit.capacity(policy.capacity()).refillGreedy(policy.capacity(), policy.refillPeriod()))
			.withCustomTimePrecision(timeMeter)
			.build();
	}
}
