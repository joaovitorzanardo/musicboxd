package com.musicboxd.api.profiles;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.musicboxd.api.db.ModuleFlyway;

@Configuration
class ProfilesConfig {

	@ConditionalOnProperty(name = "musicboxd.migrations.enabled", havingValue = "true", matchIfMissing = true)
	@Bean(initMethod = "migrate")
	Flyway profilesFlyway(DataSource dataSource) {
		return ModuleFlyway.forSchema(dataSource, "profiles");
	}
}
