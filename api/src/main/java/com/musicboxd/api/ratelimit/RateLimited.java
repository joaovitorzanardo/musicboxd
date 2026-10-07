package com.musicboxd.api.ratelimit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Applies a named per-caller limit (AD-10), configured under
 * {@code musicboxd.rate-limit.policies.<policy>}, to a controller method or class.
 * Past the limit the request gets 429 with {@code Retry-After}.
 */
@Target({ ElementType.METHOD, ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimited {

	String policy();

	/** Bean name of the {@link RateLimitKeyResolver} that identifies the caller. */
	String keyResolver() default "principalOrIp";
}
