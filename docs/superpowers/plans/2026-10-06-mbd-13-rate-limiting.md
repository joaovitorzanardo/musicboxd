# MBD-13 Rate Limiting (nginx per-IP + generic Spring per-user limiter) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Throttle bursts per client IP and cap request bodies at nginx, and ship a reusable Spring per-user rate-limit mechanism proven by a demo endpoint that returns 429 past its configured limit.

**Architecture:** nginx gets a `limit_req_zone` keyed on `$binary_remote_addr` plus `client_max_body_size`, in both the local-dev config and the prod template, with `X-Forwarded-For` overwritten (not appended) so the API sees an unspoofable client IP. In `api`, a small in-memory token-bucket `RateLimiter` (single EC2 host, no Redis, no new dependency) is exposed to endpoints through a `@RateLimited(policy=...)` annotation enforced by a `HandlerInterceptor`; policies (capacity + refill period) live in `application.yml`; the caller key comes from a pluggable `RateLimitKeyResolver` bean. A demo endpoint under `/api/v1/demo/` rides the existing springdoc/OpenAPI pipeline.

**Tech Stack:** nginx 1.27-alpine (`limit_req`), Spring Boot 4.1.1 / Java 25 / JUnit 5 / MockMvc, springdoc 3.1.1, bash tests run against Docker (same style as `deploy/tests/`).

**Spec:** `_bmad-output/initiative-musicboxd/epic-plataforma-base/story-rate-limiting-nginx-per-ip-and-a-generic-spring-per-user-lim.md` (Jira MBD-13); `ARCHITECTURE-SPINE.md` AD-10, AD-7, AD-8 (`_bmad-output/planning-artifacts/architecture/architecture-teste-2026-09-26/ARCHITECTURE-SPINE.md`).

## Global Constraints

- Two rate-limit layers: nginx `limit_req` per IP, and Spring per-user limits (AD-10).
- Request body size is capped generically; upload-specific byte limits (avatar/cover/album art) are NOT this ticket's concern.
- This ticket builds the generic mechanism only; do not apply it to login, registration, email or upload URLs (they don't exist yet).
- All API routes live under `/api/v1` and are described by springdoc-generated OpenAPI (AD-7); the demo endpoint must appear in `/api/v1/api-docs`.
- No new AWS service, no new infrastructure (no Redis); one EC2 host, so in-memory state is acceptable (AD-10, AD-11).
- Spring Security does not exist yet (AD-8 introduces it later); the limiter must not depend on it, but must pick up an authenticated principal once it exists.
- Local-dev `deploy/nginx/nginx.conf` and prod `deploy/nginx/nginx.prod.conf.template` stay in sync on the new directives; existing `deploy/tests/*` keep passing.
- Java sources use tabs, packages under `com.musicboxd.api`, tests next to the code they cover (match `health/`).

## Review Focus

- Spoofed `X-Forwarded-For` from the client: nginx must overwrite it with `$remote_addr`, otherwise any caller dodges the per-IP key by sending a fake header (Task 1 asserts the directive; Task 4 tests the resolver uses the servlet remote address, not the raw header).
- SPA page load fan-out (index.html + JS/CSS/font chunks) must not trip the nginx limit for a single normal user: burst is sized so ~30 parallel asset requests pass (Task 1 test fires 20 requests and expects zero 429s, then 100 and expects some).
- Throttled responses must be 429 (nginx default is 503, which looks like an outage and trips monitoring) and the Spring limiter must send `Retry-After` (Task 1, Task 4).
- Two different users behind the same IP must not share a Spring bucket, and one user hitting policy A must not consume policy B (Task 2 tests).
- Unbounded key growth: an attacker rotating keys must not grow the bucket map forever; when full of live keys the limiter fails closed (Task 2).
- A `@RateLimited` naming a policy missing from config must fail loudly at request time with a clear message, not silently allow everything (Task 2, Task 3).

---

## File Structure

- Modify `deploy/nginx/nginx.conf`: zone, `limit_req`, 429 status, body cap, XFF overwrite.
- Modify `deploy/nginx/nginx.prod.conf.template`: same directives in the 443 server block (zone declared at top level once).
- Create `deploy/tests/test-nginx-ratelimit.sh`: runs the real local nginx config in Docker and checks throttling and the body cap.
- Modify `deploy/tests/test-nginx-conf.sh`: assert the new directives render in the prod template.
- Create `api/src/main/java/com/musicboxd/api/ratelimit/RateLimitPolicy.java`, `RateLimitProperties.java`, `RateLimiter.java`: core mechanism, no web dependency.
- Create `.../ratelimit/RateLimited.java`, `RateLimitKeyResolver.java`, `PrincipalOrIpKeyResolver.java`, `RateLimitInterceptor.java`, `RateLimitExceededException.java`, `RateLimitWebConfig.java`: HTTP integration.
- Create `.../demo/DemoRateLimitController.java`: demo endpoint + its header-based key resolver bean.
- Modify `api/src/main/resources/application.yml`: policies + `forward-headers-strategy`.
- Create tests under `api/src/test/java/com/musicboxd/api/ratelimit/` and `.../demo/`.
- Create `deploy/runbook-mbd-13-rate-limits.md`: tunables and live verification commands.

---

### Task 1: nginx per-IP throttle, body cap, trusted client IP

**Files:**
- Modify: `deploy/nginx/nginx.conf`
- Modify: `deploy/nginx/nginx.prod.conf.template`
- Create: `deploy/tests/test-nginx-ratelimit.sh`
- Modify: `deploy/tests/test-nginx-conf.sh`

**Interfaces:**
- Consumes: existing `/api/` proxy block in both files.
- Produces: nginx returns `429` past 10 r/s per IP (burst 30), `413` for bodies over `1m`, and forwards `X-Forwarded-For: $remote_addr` (Spring's `server.forward-headers-strategy: native` in Task 3 relies on this).

- [ ] **Step 1: Write the failing test**

Create `deploy/tests/test-nginx-ratelimit.sh`:

```bash
#!/usr/bin/env bash
# deploy/tests/test-nginx-ratelimit.sh
# Boots the real local nginx config (no api needed: throttling and the body cap are
# enforced before proxying) and checks per-IP throttling and the request body cap.
set -euo pipefail
cd "$(dirname "$0")/../.."

HOST_PWD=$(cygpath -m "$PWD" 2>/dev/null || echo "$PWD")
NAME=mbd13-nginx-test
PORT=18089
cleanup() { docker rm -f "$NAME" >/dev/null 2>&1 || true; }
trap cleanup EXIT
cleanup

MSYS_NO_PATHCONV=1 docker run -d --name "$NAME" --add-host api:127.0.0.1 -p "$PORT:80" \
  -v "$HOST_PWD/deploy/nginx/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  nginx:1.27-alpine >/dev/null
for _ in $(seq 1 20); do curl -s -o /dev/null "http://localhost:$PORT/" && break; sleep 0.5; done

count_codes() { # $1 = number of requests; prints "<ok> <429>"
  local ok=0 limited=0 code
  for _ in $(seq 1 "$1"); do
    code=$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$PORT/")
    case "$code" in 200) ok=$((ok+1));; 429) limited=$((limited+1));; esac
  done
  echo "$ok $limited"
}

# A normal page load (20 quick requests) must not be throttled.
read -r OK LIMITED <<<"$(count_codes 20)"
[ "$LIMITED" -eq 0 ] || { echo "normal load throttled: $LIMITED of 20 got 429"; exit 1; }

sleep 3   # let the bucket refill
# A burst of 200 sequential requests from one IP must be throttled with 429 (not 503).
read -r OK LIMITED <<<"$(count_codes 200)"
[ "$LIMITED" -gt 0 ] || { echo "burst of 200 never got 429 (ok=$OK)"; exit 1; }
echo "burst throttled: ok=$OK limited=$LIMITED"

sleep 3
# Oversized body is rejected by nginx before it reaches the api.
BIG=$(mktemp); trap 'rm -f "$BIG"; cleanup' EXIT
head -c 2097152 /dev/zero > "$BIG"
CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST --data-binary "@$BIG" \
  -H 'Content-Type: application/octet-stream' "http://localhost:$PORT/api/v1/anything")
[ "$CODE" = "413" ] || { echo "2 MiB body got $CODE, expected 413"; exit 1; }

# The configs forward a client IP the caller cannot forge.
grep -q 'proxy_set_header X-Forwarded-For \$remote_addr;' deploy/nginx/nginx.conf
echo "nginx rate limit OK"
```

Append to `deploy/tests/test-nginx-conf.sh` just before the final `echo`:

```bash
grep -q 'limit_req_zone $binary_remote_addr zone=perip' "$TMP/out.txt"
grep -q 'limit_req zone=perip' "$TMP/out.txt"
grep -q 'limit_req_status 429' "$TMP/out.txt"
grep -q 'client_max_body_size 1m' "$TMP/out.txt"
grep -q 'proxy_set_header X-Forwarded-For $remote_addr' "$TMP/out.txt"
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `bash deploy/tests/test-nginx-ratelimit.sh; bash deploy/tests/test-nginx-conf.sh`
Expected: first fails with `burst of 200 never got 429`; second fails at the new `grep` for `limit_req_zone`.

- [ ] **Step 3: Implement in `deploy/nginx/nginx.conf`**

Add above the `server {` block, and inside the server block, replacing the X-Forwarded-For line in `/api/`:

```nginx
# AD-10 layer 1: per-IP request throttle for every route. ~10 r/s sustained with a burst
# of 30 so one SPA page load (index + chunks) passes. 429, not nginx's default 503.
limit_req_zone $binary_remote_addr zone=perip:10m rate=10r/s;

server {
    listen 80;
    server_name _;

    limit_req zone=perip burst=30 nodelay;
    limit_req_status 429;

    # Generic request body cap (AD-10). Upload-specific limits belong to the epics that own uploads.
    client_max_body_size 1m;
    # ... existing root / location blocks unchanged ...
        # Overwrite, never append: a client-supplied X-Forwarded-For must not reach the api,
        # or the per-user/IP limiter can be dodged by forging it.
        proxy_set_header X-Forwarded-For $remote_addr;
```

(Remove the old `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` line.)

- [ ] **Step 4: Implement in `deploy/nginx/nginx.prod.conf.template`**

Put the same `limit_req_zone` line once at file top level (before the first `server`). In the `listen 443 ssl` server block add `limit_req zone=perip burst=30 nodelay;`, `limit_req_status 429;`, `client_max_body_size 1m;`, and the same X-Forwarded-For overwrite in `/api/`. Do not add `limit_req` to the port-80 block (ACME challenge and redirect must stay unthrottled-simple).

- [ ] **Step 5: Run tests to verify they pass**

Run: `bash deploy/tests/test-nginx-ratelimit.sh; bash deploy/tests/test-nginx-conf.sh`
Expected: `nginx rate limit OK` and `nginx prod config OK`.

- [ ] **Step 6: Commit**

```bash
git add deploy/nginx deploy/tests
git commit -m "Add nginx per-IP rate limit, body size cap, trusted client IP (MBD-13)"
```

---

### Task 2: Per-user token-bucket limiter core

**Files:**
- Create: `api/src/main/java/com/musicboxd/api/ratelimit/RateLimitPolicy.java`
- Create: `api/src/main/java/com/musicboxd/api/ratelimit/RateLimitProperties.java`
- Create: `api/src/main/java/com/musicboxd/api/ratelimit/RateLimiter.java`
- Test: `api/src/test/java/com/musicboxd/api/ratelimit/RateLimiterTest.java`

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces:
  - `record RateLimitPolicy(int capacity, Duration refillPeriod)` (capacity tokens per `refillPeriod`, refilled continuously).
  - `@ConfigurationProperties("musicboxd.rate-limit") record RateLimitProperties(Map<String, RateLimitPolicy> policies)`.
  - `class RateLimiter` with ctor `RateLimiter(Map<String, RateLimitPolicy> policies, Clock clock, int maxTrackedKeys)` and `Decision tryAcquire(String policyName, String key)`; `record Decision(boolean allowed, Duration retryAfter)`. Unknown policy throws `IllegalArgumentException("Unknown rate limit policy: <name>")`.

- [ ] **Step 1: Write the failing test**

```java
package com.musicboxd.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

	/** Mutable clock so tests control refill without sleeping. */
	static class TestClock extends Clock {
		final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-01-01T00:00:00Z"));
		void advance(Duration d) { now.updateAndGet(i -> i.plus(d)); }
		@Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(java.time.ZoneId zone) { return this; }
		@Override public Instant instant() { return now.get(); }
	}

	private final TestClock clock = new TestClock();
	private final Map<String, RateLimitPolicy> policies = Map.of(
		"a", new RateLimitPolicy(3, Duration.ofMinutes(1)),
		"b", new RateLimitPolicy(1, Duration.ofMinutes(1)));

	private RateLimiter limiter(int maxKeys) { return new RateLimiter(policies, clock, maxKeys); }

	@Test
	void allowsUpToCapacityThenRejectsWithRetryAfter() {
		var l = limiter(100);
		for (int i = 0; i < 3; i++) assertThat(l.tryAcquire("a", "u1").allowed()).isTrue();
		var denied = l.tryAcquire("a", "u1");
		assertThat(denied.allowed()).isFalse();
		assertThat(denied.retryAfter()).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(1));
	}

	@Test
	void refillsOverTime() {
		var l = limiter(100);
		for (int i = 0; i < 3; i++) l.tryAcquire("a", "u1");
		assertThat(l.tryAcquire("a", "u1").allowed()).isFalse();
		clock.advance(Duration.ofSeconds(20));   // 1 token per 20s
		assertThat(l.tryAcquire("a", "u1").allowed()).isTrue();
		assertThat(l.tryAcquire("a", "u1").allowed()).isFalse();
	}

	@Test
	void usersAndPoliciesAreIsolated() {
		var l = limiter(100);
		for (int i = 0; i < 3; i++) l.tryAcquire("a", "u1");
		assertThat(l.tryAcquire("a", "u1").allowed()).isFalse();
		assertThat(l.tryAcquire("a", "u2").allowed()).isTrue();   // other user
		assertThat(l.tryAcquire("b", "u1").allowed()).isTrue();   // other policy
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
```

- [ ] **Step 2: Run to verify it fails**

Run: `cd api && ./gradlew test --tests '*RateLimiterTest'` (use `gradlew.bat` in PowerShell)
Expected: compilation FAIL, `RateLimiter`/`RateLimitPolicy` do not exist.

- [ ] **Step 3: Write minimal implementation**

`RateLimitPolicy.java`:

```java
package com.musicboxd.api.ratelimit;

import java.time.Duration;

/** {@code capacity} requests per {@code refillPeriod}, refilled continuously (token bucket). */
public record RateLimitPolicy(int capacity, Duration refillPeriod) {

	public RateLimitPolicy {
		if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1");
		if (refillPeriod == null || refillPeriod.isZero() || refillPeriod.isNegative())
			throw new IllegalArgumentException("refillPeriod must be positive");
	}
}
```

`RateLimitProperties.java`:

```java
package com.musicboxd.api.ratelimit;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Named policies, e.g. {@code musicboxd.rate-limit.policies.demo.capacity=5}. */
@ConfigurationProperties("musicboxd.rate-limit")
public record RateLimitProperties(Map<String, RateLimitPolicy> policies) {

	public RateLimitProperties {
		policies = policies == null ? Map.of() : Map.copyOf(policies);
	}
}
```

`RateLimiter.java`:

```java
package com.musicboxd.api.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token bucket per (policy, key). Single-host by design (AD-11): no shared store.
 * Bounded: idle buckets (a full refill period untouched, hence full) are swept when the map
 * is at {@code maxTrackedKeys}; if it is still full of live keys, new keys are rejected.
 */
public class RateLimiter {

	public record Decision(boolean allowed, Duration retryAfter) {}

	private static final class Bucket {
		double tokens;
		long lastNanos;
		Bucket(double tokens, long lastNanos) { this.tokens = tokens; this.lastNanos = lastNanos; }
	}

	private final Map<String, RateLimitPolicy> policies;
	private final Clock clock;
	private final int maxTrackedKeys;
	private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

	public RateLimiter(Map<String, RateLimitPolicy> policies, Clock clock, int maxTrackedKeys) {
		this.policies = Map.copyOf(policies);
		this.clock = clock;
		this.maxTrackedKeys = maxTrackedKeys;
	}

	public Decision tryAcquire(String policyName, String key) {
		RateLimitPolicy policy = policies.get(policyName);
		if (policy == null) throw new IllegalArgumentException("Unknown rate limit policy: " + policyName);

		long now = nanosNow();
		long periodNanos = policy.refillPeriod().toNanos();
		String id = policyName + '\u0000' + key;

		if (!buckets.containsKey(id) && buckets.size() >= maxTrackedKeys) {
			sweep(now);
			if (buckets.size() >= maxTrackedKeys) return new Decision(false, policy.refillPeriod());
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
			long wait = (long) Math.ceil(missing / policy.capacity() * periodNanos);
			return new Decision(false, Duration.ofNanos(wait));
		}
	}

	private void sweep(long now) {
		buckets.entrySet().removeIf(e -> {
			String policyName = e.getKey().substring(0, e.getKey().indexOf('\u0000'));
			long period = policies.get(policyName).refillPeriod().toNanos();
			synchronized (e.getValue()) { return now - e.getValue().lastNanos >= period; }
		});
	}

	private long nanosNow() {
		var i = clock.instant();
		return i.getEpochSecond() * 1_000_000_000L + i.getNano();
	}
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `cd api && ./gradlew test --tests '*RateLimiterTest'`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add api/src
git commit -m "Add in-memory per-key token-bucket rate limiter (MBD-13)"
```

---

### Task 3: HTTP integration and demo endpoint

**Files:**
- Create: `api/src/main/java/com/musicboxd/api/ratelimit/RateLimited.java`
- Create: `.../ratelimit/RateLimitKeyResolver.java`
- Create: `.../ratelimit/PrincipalOrIpKeyResolver.java`
- Create: `.../ratelimit/RateLimitExceededException.java`
- Create: `.../ratelimit/RateLimitInterceptor.java`
- Create: `.../ratelimit/RateLimitConfig.java` (beans + `WebMvcConfigurer`)
- Create: `api/src/main/java/com/musicboxd/api/demo/DemoRateLimitController.java`
- Modify: `api/src/main/resources/application.yml`
- Test: `api/src/test/java/com/musicboxd/api/demo/DemoRateLimitControllerTest.java`, `.../ratelimit/PrincipalOrIpKeyResolverTest.java`

**Interfaces:**
- Consumes: `RateLimiter.tryAcquire(String, String)`, `RateLimitProperties`, `RateLimitPolicy` from Task 2.
- Produces:
  - `@RateLimited(String policy, String keyResolver() default "principalOrIp")` on controller methods or classes; later epics just add this annotation.
  - `interface RateLimitKeyResolver { String resolve(HttpServletRequest request); }`; resolver beans looked up by bean name.
  - `GET /api/v1/demo/rate-limited` → 200 `{"status":"ok"}`; 429 with `Retry-After` (seconds, ceil) and body `{"title":"Too Many Requests","status":429,...}` past the `demo` policy.

- [ ] **Step 1: Write the failing tests**

`DemoRateLimitControllerTest.java` (policy overridden via properties so the test owns the numbers):

```java
package com.musicboxd.api.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
	"musicboxd.rate-limit.policies.demo.capacity=2",
	"musicboxd.rate-limit.policies.demo.refill-period=1h" })
@AutoConfigureMockMvc
class DemoRateLimitControllerTest {

	@Autowired MockMvc mvc;

	@Test
	void rejectsCallerPastItsLimitAndLeavesOthersAlone() throws Exception {
		for (int i = 0; i < 2; i++) {
			mvc.perform(get("/api/v1/demo/rate-limited").header("X-Demo-User", "alice"))
				.andExpect(status().isOk());
		}
		mvc.perform(get("/api/v1/demo/rate-limited").header("X-Demo-User", "alice"))
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.status").value(429));
		// Same IP, different user: independent bucket.
		mvc.perform(get("/api/v1/demo/rate-limited").header("X-Demo-User", "bob"))
			.andExpect(status().isOk());
	}

	@Test
	void missingDemoUserIsABadRequestNotAnUnlimitedPass() throws Exception {
		mvc.perform(get("/api/v1/demo/rate-limited")).andExpect(status().isBadRequest());
	}

	@Test
	void healthIsNotRateLimited() throws Exception {
		for (int i = 0; i < 10; i++) mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
	}
}
```

`PrincipalOrIpKeyResolverTest.java`:

```java
package com.musicboxd.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.Principal;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PrincipalOrIpKeyResolverTest {

	private final PrincipalOrIpKeyResolver resolver = new PrincipalOrIpKeyResolver();

	@Test
	void usesPrincipalWhenAuthenticated() {
		var req = new MockHttpServletRequest();
		Principal p = () -> "user-42";
		req.setUserPrincipal(p);
		assertThat(resolver.resolve(req)).isEqualTo("user:user-42");
	}

	@Test
	void fallsBackToRemoteAddrAndIgnoresRawForwardedHeader() {
		var req = new MockHttpServletRequest();
		req.setRemoteAddr("203.0.113.9");
		req.addHeader("X-Forwarded-For", "1.2.3.4");   // forgeable; only the container-resolved remoteAddr counts
		assertThat(resolver.resolve(req)).isEqualTo("ip:203.0.113.9");
	}
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd api && ./gradlew test --tests '*DemoRateLimitControllerTest' --tests '*PrincipalOrIpKeyResolverTest'`
Expected: compilation FAIL (classes missing).

- [ ] **Step 3: Implement**

`RateLimited.java`:

```java
package com.musicboxd.api.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Apply a named per-caller limit (configured under {@code musicboxd.rate-limit.policies}) to an endpoint. */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimited {
	String policy();
	/** Bean name of the {@link RateLimitKeyResolver} that identifies the caller. */
	String keyResolver() default "principalOrIp";
}
```

`RateLimitKeyResolver.java`:

```java
package com.musicboxd.api.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

public interface RateLimitKeyResolver {
	/** Stable identity of the caller; must not be derivable from client-forgeable input. */
	String resolve(HttpServletRequest request);
}
```

`PrincipalOrIpKeyResolver.java`:

```java
package com.musicboxd.api.ratelimit;

import jakarta.servlet.http.HttpServletRequest;

/** Authenticated principal when present (after AD-8 lands), else the client IP. */
public class PrincipalOrIpKeyResolver implements RateLimitKeyResolver {

	@Override
	public String resolve(HttpServletRequest request) {
		var principal = request.getUserPrincipal();
		if (principal != null) return "user:" + principal.getName();
		// Not the raw X-Forwarded-For header: with forward-headers-strategy=native the container
		// has already resolved the real client IP into remoteAddr.
		return "ip:" + request.getRemoteAddr();
	}
}
```

`RateLimitExceededException.java`:

```java
package com.musicboxd.api.ratelimit;

import java.time.Duration;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;

public class RateLimitExceededException extends ErrorResponseException {

	public RateLimitExceededException(Duration retryAfter) {
		super(HttpStatus.TOO_MANY_REQUESTS, ProblemDetail.forStatusAndDetail(
			HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded. Retry later."), null);
		getHeaders().set(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, (retryAfter.toMillis() + 999) / 1000)));
	}
}
```

(If `getHeaders()` is unmodifiable in Spring 7, override `getHeaders()` to return a fresh `HttpHeaders` containing Retry-After; verify with the test.)

`RateLimitInterceptor.java`:

```java
package com.musicboxd.api.ratelimit;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class RateLimitInterceptor implements HandlerInterceptor {

	private final RateLimiter limiter;
	private final BeanFactory beans;

	public RateLimitInterceptor(RateLimiter limiter, BeanFactory beans) {
		this.limiter = limiter;
		this.beans = beans;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod hm)) return true;
		RateLimited ann = AnnotatedElementUtils.findMergedAnnotation(hm.getMethod(), RateLimited.class);
		if (ann == null) ann = AnnotatedElementUtils.findMergedAnnotation(hm.getBeanType(), RateLimited.class);
		if (ann == null) return true;

		String key = beans.getBean(ann.keyResolver(), RateLimitKeyResolver.class).resolve(request);
		var decision = limiter.tryAcquire(ann.policy(), key);
		if (!decision.allowed()) throw new RateLimitExceededException(decision.retryAfter());
		return true;
	}
}
```

`RateLimitConfig.java`:

```java
package com.musicboxd.api.ratelimit;

import java.time.Clock;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig implements WebMvcConfigurer {

	private static final int MAX_TRACKED_KEYS = 10_000;

	private final RateLimiter limiter;
	private final BeanFactory beans;

	public RateLimitConfig(RateLimitProperties props, BeanFactory beans) {
		this.limiter = new RateLimiter(props.policies(), Clock.systemUTC(), MAX_TRACKED_KEYS);
		this.beans = beans;
	}

	@Bean("principalOrIp")
	RateLimitKeyResolver principalOrIpKeyResolver() { return new PrincipalOrIpKeyResolver(); }

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new RateLimitInterceptor(limiter, beans)).addPathPatterns("/api/**");
	}
}
```

`DemoRateLimitController.java`:

```java
package com.musicboxd.api.demo;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.musicboxd.api.ratelimit.RateLimitKeyResolver;
import com.musicboxd.api.ratelimit.RateLimited;

import io.swagger.v3.oas.annotations.responses.ApiResponse;

/**
 * Proves the per-user limiter end to end (MBD-13). The caller is a self-declared
 * {@code X-Demo-User} header because accounts (AD-8) do not exist yet; that header is
 * forgeable, which is acceptable for a demo and why real endpoints use the default
 * {@code principalOrIp} resolver. Delete this controller once a real endpoint carries
 * {@code @RateLimited}.
 */
@RestController
public class DemoRateLimitController {

	@GetMapping("/api/v1/demo/rate-limited")
	@RateLimited(policy = "demo", keyResolver = "demoUser")
	@ApiResponse(responseCode = "429", description = "Per-user limit exceeded; see Retry-After")
	public Map<String, String> rateLimited() {
		return Map.of("status", "ok");
	}

	@Component("demoUser")
	static class DemoUserKeyResolver implements RateLimitKeyResolver {
		@Override
		public String resolve(jakarta.servlet.http.HttpServletRequest request) {
			String user = request.getHeader("X-Demo-User");
			if (user == null || user.isBlank())
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "X-Demo-User header is required");
			return "demo:" + user;
		}
	}
}
```

(Remove the unused `Bean` import.) `application.yml`, append and extend:

```yaml
server:
  port: 8080
  # Trust the client IP nginx forwards (nginx overwrites X-Forwarded-For, see deploy/nginx).
  forward-headers-strategy: native

musicboxd:
  rate-limit:
    policies:
      demo:
        capacity: 5
        refill-period: 1m
```

- [ ] **Step 4: Run to verify they pass**

Run: `cd api && ./gradlew test`
Expected: all tests PASS including pre-existing `HealthControllerTest`. If Boot 4 package names for `@AutoConfigureMockMvc` differ, fix the import (`org.springframework.boot.webmvc.test.autoconfigure`, as `@WebMvcTest` already uses).

- [ ] **Step 5: Commit**

```bash
git add api/src
git commit -m "Add @RateLimited per-user limiting with demo endpoint (MBD-13)"
```

---

### Task 4: OpenAPI contract, runbook, end-to-end verification

**Files:**
- Test: `api/src/test/java/com/musicboxd/api/demo/DemoOpenApiTest.java`
- Create: `deploy/runbook-mbd-13-rate-limits.md`

**Interfaces:**
- Consumes: demo endpoint from Task 3; nginx directives from Task 1.
- Produces: documented tunables and the AC verification procedure.

- [ ] **Step 1: Write the failing test**

```java
package com.musicboxd.api.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** The demo route must ride the springdoc pipeline (AD-7) so the generated TS client sees it, 429 included. */
@SpringBootTest
@AutoConfigureMockMvc
class DemoOpenApiTest {

	@Autowired MockMvc mvc;

	@Test
	void demoEndpointAndItsTooManyRequestsResponseAreInTheContract() throws Exception {
		mvc.perform(get("/api/v1/api-docs"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.paths['/api/v1/demo/rate-limited'].get.responses['429']").exists());
	}
}
```

- [ ] **Step 2: Run, expect PASS or fix**

Run: `cd api && ./gradlew test --tests '*DemoOpenApiTest'`
Expected: PASS (Task 3 already added `@ApiResponse`). If FAIL, the annotation is not picked up: confirm the `io.swagger` annotations are on the classpath via `springdoc-openapi-starter-webmvc-api` and fix before continuing.

- [ ] **Step 3: Verify client generation still works**

Run: `cd api && ./gradlew generateOpenApiDocs && cd ../web && npm --prefix tools/openapi-ts-gen run generate` (use the script name from `web/package.json`; `npm run build` in `web/` also runs it).
Expected: generated client contains the demo operation; no manual edits.

- [ ] **Step 4: Write the runbook**

Create `deploy/runbook-mbd-13-rate-limits.md` with: (a) tunables and where they live (`rate=10r/s burst=30`, `client_max_body_size 1m` in both nginx files; `musicboxd.rate-limit.policies.*` in `application.yml`, overridable by env `MUSICBOXD_RATELIMIT_POLICIES_DEMO_CAPACITY`); (b) live verification on the host, copied exactly so it can be pasted:

```bash
# nginx per-IP: expect a mix of 200 and 429
for i in $(seq 1 150); do curl -s -o /dev/null -w '%{http_code}\n' https://$DOMAIN/; done | sort | uniq -c
# body cap: expect 413
head -c 2097152 /dev/zero | curl -s -o /dev/null -w '%{http_code}\n' -X POST --data-binary @- https://$DOMAIN/api/v1/anything
# per-user limiter: 5 x 200 then 429 with Retry-After; a different user still 200
for i in $(seq 1 7); do curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Demo-User: alice' https://$DOMAIN/api/v1/demo/rate-limited; done
curl -si -H 'X-Demo-User: alice' https://$DOMAIN/api/v1/demo/rate-limited | grep -i '^retry-after'
curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Demo-User: bob' https://$DOMAIN/api/v1/demo/rate-limited
```

Note in (b) that the nginx burst check must run from one client and may need to be repeated after a few seconds of idle; (c) a results table to fill in during verification, matching `runbook-mbd-10-cd-verify.md` style.

- [ ] **Step 5: Full local regression**

Run: `cd api && ./gradlew test` and `bash deploy/tests/test-nginx-conf.sh && bash deploy/tests/test-nginx-ratelimit.sh && bash deploy/tests/test-compose-config.sh`
Expected: all green.

- [ ] **Step 6: Commit**

```bash
git add api/src deploy/runbook-mbd-13-rate-limits.md
git commit -m "Pin demo limiter in OpenAPI contract and document verification (MBD-13)"
```

---

## Self-Review

- **Spec coverage:** nginx `limit_req` for all routes → Task 1 (both configs); request body cap → Task 1 (`client_max_body_size`); reusable per-user mechanism → Tasks 2-3; demo endpoint rejecting past limit via OpenAPI pipeline → Task 3 and 4; AC "burst throttled / oversized rejected / demo rejects" → Task 1 script, Task 3 test, Task 4 runbook. Upload-specific limits and applying to real endpoints are explicitly out of scope.
- **Placeholder scan:** none. Two flagged API-uncertainty notes (Spring 7 `ErrorResponseException` headers, Boot 4 test package) each give the concrete fallback.
- **Type consistency:** `RateLimiter.tryAcquire(String,String)` → `Decision(allowed, retryAfter)`, `RateLimitPolicy(capacity, refillPeriod)`, `RateLimitKeyResolver.resolve(HttpServletRequest)` and the bean names `principalOrIp` / `demoUser` are used identically across tasks.
- **Open risks:** (1) in-memory state resets on each deploy/restart, so a caller regains a full bucket; acceptable on one host, revisit if limits ever guard something costly like email sending. (2) The nginx burst value (30) is a judgment call for SPA chunk fan-out; tune in the runbook if real page loads trip it.
