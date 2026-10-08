package com.musicboxd.api.accounts;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param linkBaseUrl where verification links point, e.g. {@code https://musicboxd.com.br}
 *        (env MUSICBOXD_PUBLIC_BASE_URL); no default, a wrong value sends people to a dead link
 * @param mailFrom sender, e.g. {@code no-reply@musicboxd.com.br} (env MUSICBOXD_MAIL_FROM); must be on
 *        the verified SES domain, and the host's IAM policy allows only this address
 * @param ttl how long a link stays valid
 */
@ConfigurationProperties("musicboxd.verification")
public record VerificationProperties(URI linkBaseUrl, String mailFrom, @DefaultValue("24h") Duration ttl) {

	public VerificationProperties {
		if (linkBaseUrl == null || !linkBaseUrl.isAbsolute() || !Set.of("http", "https").contains(linkBaseUrl.getScheme())
				|| linkBaseUrl.getHost() == null) {
			throw new IllegalStateException(
					"musicboxd.verification.link-base-url (env MUSICBOXD_PUBLIC_BASE_URL) must be set to an absolute http(s) URL");
		}
		if (mailFrom == null || mailFrom.isBlank()) {
			throw new IllegalStateException("musicboxd.verification.mail-from (env MUSICBOXD_MAIL_FROM) must be set");
		}
	}
}
