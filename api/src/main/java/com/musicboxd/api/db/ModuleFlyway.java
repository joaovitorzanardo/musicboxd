package com.musicboxd.api.db;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;

/**
 * One Flyway instance per module schema (spine: Migrations). A module's migrations live in
 * {@code db/migration/<schema>} and its history table lives in the schema it tracks, so
 * modules never share or order each other's migrations.
 */
public final class ModuleFlyway {

	private ModuleFlyway() {
	}

	public static Flyway forSchema(DataSource dataSource, String schema) {
		return Flyway.configure()
			.dataSource(dataSource)
			.schemas(schema)
			.defaultSchema(schema)
			.createSchemas(true)
			.locations("classpath:db/migration/" + schema)
			.load();
	}
}
