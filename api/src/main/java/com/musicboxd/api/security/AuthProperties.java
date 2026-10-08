package com.musicboxd.api.security;

import java.time.Duration;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param jwtSecret Base64 of at least 32 random bytes (HS256); generate with
 *        {@code openssl rand -base64 32}. Validated here so a bad secret stops startup.
 * @param accessTokenTtl access-token lifetime, about 15 minutes (AD-8)
 */
@ConfigurationProperties("musicboxd.auth")
public record AuthProperties(String jwtSecret, @DefaultValue("15m") Duration accessTokenTtl) {

	private static final int MIN_SECRET_BYTES = 32;

	public AuthProperties {
		if (jwtSecret == null || jwtSecret.isBlank()) {
			throw new IllegalStateException("musicboxd.auth.jwt-secret (env MUSICBOXD_JWT_SECRET) must be set");
		}
		if (decode(jwtSecret).length < MIN_SECRET_BYTES) {
			throw new IllegalStateException(
					"musicboxd.auth.jwt-secret must decode to at least " + MIN_SECRET_BYTES + " bytes");
		}
	}

	@Override
	public String toString() {
		return "AuthProperties[jwtSecret=***, accessTokenTtl=" + accessTokenTtl + "]";
	}

	public SecretKey signingKey() {
		return new SecretKeySpec(decode(jwtSecret), "HmacSHA256");
	}

	private static byte[] decode(String secret) {
		try {
			return Base64.getDecoder().decode(secret.strip());
		}
		catch (IllegalArgumentException e) {
			throw new IllegalStateException("musicboxd.auth.jwt-secret must be Base64", e);
		}
	}
}
