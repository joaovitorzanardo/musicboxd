package com.musicboxd.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmailRateLimitKeyTest {

	@Test
	void sameAddressInAnyCaseOrPaddingSharesAKey() {
		assertThat(EmailRateLimitKey.of("  Ana@Exemplo.COM ")).isEqualTo(EmailRateLimitKey.of("ana@exemplo.com"));
	}

	@Test
	void differentAddressesGetDifferentKeys() {
		assertThat(EmailRateLimitKey.of("ana@exemplo.com")).isNotEqualTo(EmailRateLimitKey.of("bia@exemplo.com"));
	}

	@Test
	void keyLengthIsFixedWhateverTheInput() {
		String huge = EmailRateLimitKey.of("a".repeat(100_000) + "@exemplo.com");

		assertThat(huge).startsWith("email:").hasSize(EmailRateLimitKey.of("a@b").length());
		assertThat(huge).doesNotContain("exemplo");
	}
}
