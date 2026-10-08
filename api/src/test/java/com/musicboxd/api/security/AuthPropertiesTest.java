package com.musicboxd.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Base64;

import org.junit.jupiter.api.Test;

class AuthPropertiesTest {

	private static final Duration TTL = Duration.ofMinutes(15);

	@Test
	void missingSecretFailsStartupWithTheEnvVarName() {
		assertThatThrownBy(() -> new AuthProperties("", TTL))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("MUSICBOXD_JWT_SECRET");
		assertThatThrownBy(() -> new AuthProperties(null, TTL)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void nonBase64SecretIsRejected() {
		assertThatThrownBy(() -> new AuthProperties("not base64 !!!", TTL))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void secretShorterThan32BytesIsRejected() {
		String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);
		assertThatThrownBy(() -> new AuthProperties(sixteenBytes, TTL))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("32 bytes");
	}

	@Test
	void validSecretGivesAnHmacSha256Key() {
		String secret = Base64.getEncoder().encodeToString(new byte[32]);
		assertThat(new AuthProperties(secret, TTL).signingKey().getAlgorithm()).isEqualTo("HmacSHA256");
	}

	@Test
	void toStringDoesNotLeakTheSecret() {
		String secret = Base64.getEncoder().encodeToString(new byte[32]);
		assertThat(new AuthProperties(secret, TTL).toString()).doesNotContain(secret).contains("***");
	}
}
