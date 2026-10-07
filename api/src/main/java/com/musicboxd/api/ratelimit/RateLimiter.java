package com.musicboxd.api.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token bucket per (policy, key). Single-host by design (AD-11): no shared
 * store, so state resets on restart. Memory is bounded per policy: when a policy holds
 * {@code maxTrackedKeys} buckets, idle ones (untouched for a full refill period, hence full
 * again) are swept, and if none are idle the least recently used bucket is evicted. A key
 * flood therefore costs at most one extra request for an evicted caller; it never locks
 * out new callers, and never touches another policy's buckets.
 */
public class RateLimiter {

	public record Decision(boolean allowed, Duration retryAfter) {
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
	private final Map<String, ConcurrentHashMap<String, Bucket>> bucketsByPolicy = new HashMap<>();
	private final Clock clock;
	private final int maxTrackedKeys;

	public RateLimiter(Map<String, RateLimitPolicy> policies, Clock clock, int maxTrackedKeys) {
		this.policies = Map.copyOf(policies);
		this.policies.keySet().forEach(name -> bucketsByPolicy.put(name, new ConcurrentHashMap<>()));
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
		ConcurrentHashMap<String, Bucket> buckets = bucketsByPolicy.get(policyName);

		if (!buckets.containsKey(key) && buckets.size() >= maxTrackedKeys) {
			makeRoom(buckets, now, periodNanos);
		}

		Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(policy.capacity(), now));
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

	/** Drops idle buckets; if none were idle, drops the least recently used one. */
	private static void makeRoom(ConcurrentHashMap<String, Bucket> buckets, long now, long periodNanos) {
		String lruKey = null;
		long lruNanos = Long.MAX_VALUE;
		boolean sweptAny = false;
		for (var entry : buckets.entrySet()) {
			long last;
			synchronized (entry.getValue()) {
				last = entry.getValue().lastNanos;
			}
			if (now - last >= periodNanos) {
				sweptAny |= buckets.remove(entry.getKey(), entry.getValue());
			}
			else if (last < lruNanos) {
				lruNanos = last;
				lruKey = entry.getKey();
			}
		}
		if (!sweptAny && lruKey != null) {
			buckets.remove(lruKey);
		}
	}

	private long nanosNow() {
		Instant i = clock.instant();
		return i.getEpochSecond() * 1_000_000_000L + i.getNano();
	}
}
