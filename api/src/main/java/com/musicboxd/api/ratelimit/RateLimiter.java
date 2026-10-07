package com.musicboxd.api.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token bucket per (policy, key). Single-host by design (AD-11): no shared
 * store, so state resets on restart. Bounded: when {@code maxTrackedKeys} buckets exist,
 * idle ones (untouched for a full refill period, hence full again) are swept; if it is
 * still full of live keys, new keys are rejected rather than admitted unlimited.
 */
public class RateLimiter {

	public record Decision(boolean allowed, Duration retryAfter) {
	}

	private record BucketId(String policy, String key) {
	}

	private static final class Bucket {

		double tokens;
		long lastNanos;

		Bucket(double tokens, long lastNanos) {
			this.tokens = tokens;
			this.lastNanos = lastNanos;
		}
	}

	private final Map<String, RateLimitPolicy> policies;
	private final Clock clock;
	private final int maxTrackedKeys;
	private final ConcurrentHashMap<BucketId, Bucket> buckets = new ConcurrentHashMap<>();

	public RateLimiter(Map<String, RateLimitPolicy> policies, Clock clock, int maxTrackedKeys) {
		this.policies = Map.copyOf(policies);
		this.clock = clock;
		this.maxTrackedKeys = maxTrackedKeys;
	}

	public Decision tryAcquire(String policyName, String key) {
		RateLimitPolicy policy = policies.get(policyName);
		if (policy == null) {
			throw new IllegalArgumentException("Unknown rate limit policy: " + policyName);
		}

		long now = nanosNow();
		long periodNanos = policy.refillPeriod().toNanos();
		BucketId id = new BucketId(policyName, key);

		if (!buckets.containsKey(id) && buckets.size() >= maxTrackedKeys) {
			sweep(now);
			if (buckets.size() >= maxTrackedKeys) {
				return new Decision(false, policy.refillPeriod());
			}
		}

		Bucket bucket = buckets.computeIfAbsent(id, k -> new Bucket(policy.capacity(), now));
		synchronized (bucket) {
			double refill = (double) (now - bucket.lastNanos) / periodNanos * policy.capacity();
			bucket.tokens = Math.min(policy.capacity(), bucket.tokens + refill);
			bucket.lastNanos = now;
			if (bucket.tokens >= 1) {
				bucket.tokens -= 1;
				return new Decision(true, Duration.ZERO);
			}
			double missing = 1 - bucket.tokens;
			long waitNanos = (long) Math.ceil(missing / policy.capacity() * periodNanos);
			return new Decision(false, Duration.ofNanos(waitNanos));
		}
	}

	private void sweep(long now) {
		buckets.entrySet().removeIf(e -> {
			long periodNanos = policies.get(e.getKey().policy()).refillPeriod().toNanos();
			synchronized (e.getValue()) {
				return now - e.getValue().lastNanos >= periodNanos;
			}
		});
	}

	private long nanosNow() {
		Instant i = clock.instant();
		return i.getEpochSecond() * 1_000_000_000L + i.getNano();
	}
}
