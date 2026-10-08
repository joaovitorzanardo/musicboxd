package com.musicboxd.api.accounts;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import com.musicboxd.api.security.AuthProperties;
import com.musicboxd.api.security.JwtConfig;

/** Issues short-lived access tokens (AD-8). Refresh tokens are MBD-19. */
@Service
public class TokenService {

	public record AccessToken(String value, long expiresInSeconds) {
	}

	private final JwtEncoder encoder;
	private final AuthProperties props;
	private final Clock clock;

	TokenService(JwtEncoder encoder, AuthProperties props, Clock clock) {
		this.encoder = encoder;
		this.props = props;
		this.clock = clock;
	}

	public AccessToken issue(UUID accountId) {
		Instant now = clock.instant();
		var claims = JwtClaimsSet.builder()
			.issuer(JwtConfig.ISSUER)
			.subject(accountId.toString())
			.issuedAt(now)
			.expiresAt(now.plus(props.accessTokenTtl()))
			.build();
		var header = JwsHeader.with(MacAlgorithm.HS256).build();
		String value = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
		return new AccessToken(value, props.accessTokenTtl().toSeconds());
	}
}
