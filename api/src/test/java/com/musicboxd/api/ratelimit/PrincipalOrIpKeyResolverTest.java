package com.musicboxd.api.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PrincipalOrIpKeyResolverTest {

	private final PrincipalOrIpKeyResolver resolver = new PrincipalOrIpKeyResolver();

	@Test
	void usesPrincipalWhenAuthenticated() {
		var request = new MockHttpServletRequest();
		request.setUserPrincipal(() -> "user-42");
		assertThat(resolver.resolve(request)).isEqualTo("user:user-42");
	}

	@Test
	void fallsBackToRemoteAddrAndIgnoresRawForwardedHeader() {
		var request = new MockHttpServletRequest();
		request.setRemoteAddr("203.0.113.9");
		// Forgeable; only the container-resolved remoteAddr counts.
		request.addHeader("X-Forwarded-For", "1.2.3.4");
		assertThat(resolver.resolve(request)).isEqualTo("ip:203.0.113.9");
	}
}
