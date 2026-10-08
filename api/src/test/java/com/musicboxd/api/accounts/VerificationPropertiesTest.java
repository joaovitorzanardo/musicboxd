package com.musicboxd.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class VerificationPropertiesTest {

	private static final String FROM = "no-reply@musicboxd.com.br";
	private static final Duration DAY = Duration.ofHours(24);

	@Test
	void linkBaseUrlIsRequiredAndMustBeAbsoluteHttp() {
		for (String bad : new String[] { "", "musicboxd.com.br", "/relative", "ftp://musicboxd.com.br" }) {
			assertThatThrownBy(() -> new VerificationProperties(URI.create(bad), FROM, DAY))
				.as(bad).isInstanceOf(IllegalStateException.class).hasMessageContaining("MUSICBOXD_PUBLIC_BASE_URL");
		}
		assertThatThrownBy(() -> new VerificationProperties(null, FROM, DAY))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void mailFromIsRequired() {
		URI base = URI.create("https://musicboxd.com.br");
		assertThatThrownBy(() -> new VerificationProperties(base, " ", DAY))
			.isInstanceOf(IllegalStateException.class).hasMessageContaining("MUSICBOXD_MAIL_FROM");
		assertThatThrownBy(() -> new VerificationProperties(base, null, DAY))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void validConfigBinds() {
		var props = new VerificationProperties(URI.create("https://musicboxd.com.br"), FROM, DAY);
		assertThat(props.ttl()).isEqualTo(DAY);
	}
}
