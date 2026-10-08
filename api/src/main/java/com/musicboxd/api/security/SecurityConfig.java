package com.musicboxd.api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.DispatcherType;

/**
 * Route-level gating (AD-7, AD-8): public GETs need no token, every write needs a valid
 * bearer JWT. Roles and /api/v1/admin/** arrive with MBD-21.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
		AuthenticationEntryPoint entryPoint = new ProblemDetailsAuthenticationEntryPoint();
		http
			// Bearer tokens are not cookie-borne, so CSRF does not apply to them. The one cookie, MBD-19's
			// refresh token, is SameSite=Lax (no cross-site POSTs) and scoped to /api/v1/auth/refresh (AD-8).
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				// Error dispatches carry the original status; don't turn them into 401s.
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
						"/api/v1/auth/verification-email", "/api/v1/auth/refresh").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/auth/verify").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/accounts/me").authenticated()
				// GUARD: any authenticated GET must be listed ABOVE this line, or it becomes public.
				.requestMatchers(HttpMethod.GET, "/api/**").permitAll()
				.anyRequest().authenticated())
			.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
			.oauth2ResourceServer(oauth -> oauth
				.bearerTokenResolver(bearerTokenResolver())
				.jwt(Customizer.withDefaults())
				.authenticationEntryPoint(entryPoint));
		return http.build();
	}

	/**
	 * Auth endpoints are public and never read a bearer token. Without this, an expired JWT that an SPA
	 * interceptor attaches to every call would make the refresh itself fail with 401.
	 */
	private static BearerTokenResolver bearerTokenResolver() {
		var defaults = new DefaultBearerTokenResolver();
		return request -> request.getRequestURI().startsWith("/api/v1/auth/") ? null : defaults.resolve(request);
	}
}
