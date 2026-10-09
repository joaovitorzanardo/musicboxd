package com.musicboxd.api.ratelimit;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Wires the per-user limiter (AD-10 layer 2) into every {@code /api/**} request, and exposes it to
 * controllers as {@link RateLimits}.
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfig implements WebMvcConfigurer {

	/** Bounds limiter memory against key-rotating callers; see {@link RateLimiter}. */
	private static final int MAX_TRACKED_KEYS = 10_000;

	private final RateLimits rateLimits;
	private final BeanFactory beans;

	public RateLimitConfig(RateLimitProperties properties, BeanFactory beans) {
		this.rateLimits = new RateLimits(new RateLimiter(properties.policies(), MAX_TRACKED_KEYS));
		this.beans = beans;
	}

	/** One instance for the interceptor and for controllers, so both draw from the same buckets. */
	@Bean
	RateLimits rateLimits() {
		return rateLimits;
	}

	@Bean("principalOrIp")
	RateLimitKeyResolver principalOrIpKeyResolver() {
		return new PrincipalOrIpKeyResolver();
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(new RateLimitInterceptor(rateLimits, beans)).addPathPatterns("/api/**");
	}
}
