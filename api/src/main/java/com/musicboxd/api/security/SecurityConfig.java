package com.musicboxd.api.security;

import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import jakarta.servlet.DispatcherType;

/**
 * Route-level gating (AD-7, AD-8): public GETs need no token, every write needs a USER or STAFF token,
 * and /api/v1/admin/** needs STAFF for every method (MBD-21). The role comes from the access token's
 * {@code role} claim, which only the API issues.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private static final Set<String> PUBLIC_AUTH_PATHS = Set.of("/api/v1/auth/register", "/api/v1/auth/login",
			"/api/v1/auth/verification-email", "/api/v1/auth/verify", "/api/v1/auth/refresh");

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http) throws Exception {
		AuthenticationEntryPoint entryPoint = new ProblemDetailsAuthenticationEntryPoint();
		AccessDeniedHandler accessDenied = new ProblemDetailsAccessDeniedHandler();
		http
			// Bearer tokens are not cookie-borne, so CSRF does not apply to them. The one cookie, MBD-19's
			// refresh token, is SameSite=Lax (no cross-site POST/DELETE) and scoped to /api/v1/auth/refresh,
			// where POST refreshes and DELETE logs out (AD-8). A forged logout would only log the person out.
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(auth -> auth
				// Error dispatches carry the original status; don't turn them into 401s.
				.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				// GUARD: first rule, every method. Below it, the public-GET rule would open admin reads.
				// "/**" also matches the bare /api/v1/admin.
				.requestMatchers("/api/v1/admin/**").hasRole("STAFF")
				.requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login",
						"/api/v1/auth/verification-email", "/api/v1/auth/refresh").permitAll()
				// Logout: the refresh cookie is the credential; an expired access token must not block it.
				.requestMatchers(HttpMethod.DELETE, "/api/v1/auth/refresh").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/auth/verify").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/v1/accounts/me").hasAnyRole("USER", "STAFF")
				// GUARD: any authenticated GET must be listed ABOVE this line, or it becomes public.
				.requestMatchers(HttpMethod.GET, "/api/**").permitAll()
				.anyRequest().hasAnyRole("USER", "STAFF"))
			.exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(accessDenied))
			.oauth2ResourceServer(oauth -> oauth
				.bearerTokenResolver(bearerTokenResolver())
				.jwt(jwt -> jwt.jwtAuthenticationConverter(roleClaimConverter()))
				.authenticationEntryPoint(entryPoint)
				.accessDeniedHandler(accessDenied));
		return http.build();
	}

	/**
	 * The public auth endpoints never read a bearer token. Without this, an expired JWT that an SPA
	 * interceptor attaches to every call would make the refresh itself fail with 401. Exact paths, not a
	 * prefix: any other /api/v1/auth route still authenticates with its bearer token.
	 */
	private static BearerTokenResolver bearerTokenResolver() {
		var defaults = new DefaultBearerTokenResolver();
		return request -> PUBLIC_AUTH_PATHS.contains(request.getRequestURI()) ? null : defaults.resolve(request);
	}

	/**
	 * {@code "role": "STAFF"} becomes the authority {@code ROLE_STAFF}. A token without the claim (issued
	 * before MBD-21) has no role and is refused on writes and admin until it is refreshed.
	 */
	private static JwtAuthenticationConverter roleClaimConverter() {
		var authorities = new JwtGrantedAuthoritiesConverter();
		authorities.setAuthoritiesClaimName(JwtConfig.ROLE_CLAIM);
		authorities.setAuthorityPrefix("ROLE_");
		var converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authorities);
		return converter;
	}
}
