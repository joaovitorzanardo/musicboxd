package com.musicboxd.api.security;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

/**
 * Access tokens are HS256 JWTs (AD-8): the API is the only issuer and the only verifier,
 * so one shared secret is enough and no key distribution is needed.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {

	public static final String ISSUER = "musicboxd";

	@Bean
	public JwtEncoder jwtEncoder(AuthProperties props) {
		return new NimbusJwtEncoder(new ImmutableSecret<>(props.signingKey()));
	}

	@Bean
	public JwtDecoder jwtDecoder(AuthProperties props) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(props.signingKey())
			.macAlgorithm(MacAlgorithm.HS256)
			.build();
		// Default validators check exp/nbf (60 s skew); add the issuer.
		decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
		return decoder;
	}

	@Bean
	public Clock clock() {
		return Clock.systemUTC();
	}
}
