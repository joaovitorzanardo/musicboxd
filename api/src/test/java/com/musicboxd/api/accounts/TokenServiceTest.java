package com.musicboxd.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidationException;

import com.musicboxd.api.security.AuthProperties;
import com.musicboxd.api.security.JwtConfig;

class TokenServiceTest {

	private static final String SECRET = "dGVzdC1vbmx5LWp3dC1zZWNyZXQtbm90LWZvci1wcm9kLXVzZSEh";

	private final AuthProperties props = new AuthProperties(SECRET, Duration.ofMinutes(15));
	private final JwtConfig jwt = new JwtConfig();
	private final JwtDecoder decoder = jwt.jwtDecoder(props);

	@Test
	void tokenCarriesTheAccountIdAndExpiresInFifteenMinutes() {
		var accountId = UUID.randomUUID();
		var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

		var token = serviceAt(now, props).issue(accountId);
		Jwt decoded = decoder.decode(token.value());

		assertThat(decoded.getSubject()).isEqualTo(accountId.toString());
		assertThat(decoded.getClaimAsString("iss")).isEqualTo(JwtConfig.ISSUER);
		assertThat(decoded.getIssuedAt()).isEqualTo(now);
		assertThat(decoded.getExpiresAt()).isEqualTo(now.plus(Duration.ofMinutes(15)));
		assertThat(token.expiresInSeconds()).isEqualTo(900);
	}

	@Test
	void expiredTokenIsRejected() {
		var token = serviceAt(Instant.now().minus(Duration.ofHours(1)), props).issue(UUID.randomUUID());
		assertThatThrownBy(() -> decoder.decode(token.value())).isInstanceOf(JwtValidationException.class);
	}

	@Test
	void tokenSignedWithAnotherSecretIsRejected() {
		String other = Base64.getEncoder()
			.encodeToString("another-secret-of-at-least-32-bytes!!".getBytes(StandardCharsets.UTF_8));
		var foreign = serviceAt(Instant.now(), new AuthProperties(other, Duration.ofMinutes(15)))
			.issue(UUID.randomUUID());
		assertThatThrownBy(() -> decoder.decode(foreign.value())).isInstanceOf(JwtException.class);
	}

	private TokenService serviceAt(Instant now, AuthProperties signingProps) {
		return new TokenService(jwt.jwtEncoder(signingProps), signingProps, Clock.fixed(now, ZoneOffset.UTC));
	}
}
