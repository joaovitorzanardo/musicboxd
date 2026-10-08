package com.musicboxd.api.db;

import java.util.Map;

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
		return forSchema(dataSource, schema, Map.of());
	}

	/** Placeholders are substituted as plain text into the module's SQL: validate any value that comes from outside. */
	public static Flyway forSchema(DataSource dataSource, String schema, Map<String, String> placeholders) {
		return Flyway.configure()
			.dataSource(dataSource)
			.schemas(schema)
			.defaultSchema(schema)
			.createSchemas(true)
			.locations("classpath:db/migration/" + schema)
			.placeholders(placeholders)
			.load();
	}
}
