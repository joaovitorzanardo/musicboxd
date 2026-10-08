package com.musicboxd.api.accounts;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
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

	// Off only for the OpenAPI export boot (web/Dockerfile), which has no database.
	@ConditionalOnProperty(name = "musicboxd.migrations.enabled", havingValue = "true", matchIfMissing = true)
	@Bean(initMethod = "migrate")
	Flyway accountsFlyway(DataSource dataSource) {
		return ModuleFlyway.forSchema(dataSource, "accounts");
	}

	/** BCrypt today (AD-8); the stored {bcrypt} prefix keeps a later switch to Argon2 possible. */
	@Bean
	PasswordEncoder passwordEncoder() {
		return PasswordEncoderFactories.createDelegatingPasswordEncoder();
	}
}
