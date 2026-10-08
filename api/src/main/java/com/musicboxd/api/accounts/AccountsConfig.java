package com.musicboxd.api.accounts;

import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.musicboxd.api.db.ModuleFlyway;

@Configuration
@EnableConfigurationProperties({ VerificationProperties.class, RefreshTokenProperties.class })
class AccountsConfig {

	static final String STAFF_EMAIL_PLACEHOLDER = "bootstrap_staff_email";

	// Off only for the OpenAPI export boot (web/Dockerfile), which has no database.
	@ConditionalOnProperty(name = "musicboxd.migrations.enabled", havingValue = "true", matchIfMissing = true)
	@Bean(initMethod = "migrate")
	Flyway accountsFlyway(DataSource dataSource, @Value("${musicboxd.bootstrap-staff-email:}") String staffEmail) {
		return ModuleFlyway.forSchema(dataSource, "accounts",
				Map.of(STAFF_EMAIL_PLACEHOLDER, bootstrapStaffEmail(staffEmail)));
	}

	/**
	 * The value is pasted into SQL by Flyway, so anything that is not a plain address stops startup.
	 * Lowercased like every stored email (AccountService.normalize).
	 */
	static String bootstrapStaffEmail(String raw) {
		if (raw == null || raw.isBlank()) {
			return "";
		}
		String email = AccountService.normalize(raw);
		if (!email.matches("[^'\\s@]+@[^'\\s@]+")) {
			throw new IllegalStateException(
					"musicboxd.bootstrap-staff-email (env MUSICBOXD_BOOTSTRAP_STAFF_EMAIL) must be a plain email address");
		}
		return email;
	}

	/** BCrypt today (AD-8); the stored {bcrypt} prefix keeps a later switch to Argon2 possible. */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}
}
