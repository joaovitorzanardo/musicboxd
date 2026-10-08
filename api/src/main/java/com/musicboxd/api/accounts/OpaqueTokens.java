package com.musicboxd.api.accounts;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Random bearer secrets that are stored only as SHA-256 (AD-8): verification links and refresh tokens.
 * A fast hash is safe because the input has 256 bits of entropy, and it must be deterministic to look rows up.
 */
final class OpaqueTokens {

	private static final int TOKEN_BYTES = 32;
	private static final SecureRandom RANDOM = new SecureRandom();

	private OpaqueTokens() {
	}

	static String generate() {
		byte[] bytes = new byte[TOKEN_BYTES];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static String hash(String rawToken) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(UTF_8)));
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is required by every JVM", e);
		}
	}
}
