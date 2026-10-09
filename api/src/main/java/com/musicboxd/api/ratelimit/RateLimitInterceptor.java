package com.musicboxd.api.ratelimit;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Enforces {@link RateLimited} on the matched handler method, or its controller class. */
public class RateLimitInterceptor implements HandlerInterceptor {

	private final RateLimits rateLimits;
	private final BeanFactory beans;

	public RateLimitInterceptor(RateLimits rateLimits, BeanFactory beans) {
		this.rateLimits = rateLimits;
		this.beans = beans;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		if (!(handler instanceof HandlerMethod method)) {
			return true;
		}
		RateLimited limit = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), RateLimited.class);
		if (limit == null) {
			limit = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RateLimited.class);
		}
		if (limit == null) {
			return true;
		}

		String key = beans.getBean(limit.keyResolver(), RateLimitKeyResolver.class).resolve(request);
		rateLimits.check(limit.policy(), key);
		return true;
	}
}
