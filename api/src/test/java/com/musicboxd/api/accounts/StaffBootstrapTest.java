package com.musicboxd.api.accounts;

import static com.musicboxd.api.accounts.AccountServiceTest.uniqueEmail;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.db.ModuleFlyway;

/** MBD-21: the first STAFF account comes from MUSICBOXD_BOOTSTRAP_STAFF_EMAIL through an afterMigrate callback. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class StaffBootstrapTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void theConfiguredEmailIsPromotedCaseInsensitively() {
		String email = uniqueEmail();
		UUID id = insertAccount(email);
		UUID bystander = insertAccount(uniqueEmail());

		migrateWith(email.toUpperCase());

		assertThat(role(id)).isEqualTo("STAFF");
		assertThat(role(bystander)).isEqualTo("USER");
	}

	@Test
	void promotionRerunsOnEveryMigrate() {
		// First deploy: the variable is set before the person registered, so nobody matches.
		String email = uniqueEmail();
		migrateWith(email);

		// They register; the next start (same variable, nothing else changed) promotes them.
		UUID id = insertAccount(email);
		migrateWith(email);

		assertThat(role(id)).isEqualTo("STAFF");
	}

	@Test
	void anEmptyVariablePromotesNobodyAndNothingDemotes() {
		String email = uniqueEmail();
		UUID id = insertAccount(email);
		migrateWith(email);
		Integer staffBefore = staffCount();

		migrateWith("");

		assertThat(staffCount()).isEqualTo(staffBefore);
		assertThat(role(id)).isEqualTo("STAFF");
	}

	@Test
	void anUnverifiedAccountIsNotPromoted() {
		String email = uniqueEmail();
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO accounts.accounts (id, email, password_hash) VALUES (:id, :email, '{noop}x')")
			.param("id", id).param("email", email).update();

		migrateWith(email);

		assertThat(role(id)).isEqualTo("USER");
	}

	@Test
	void promotionLeavesNoSchemaHistoryRow() {
		String email = uniqueEmail();
		insertAccount(email);
		Integer before = historyRows();

		migrateWith(email);

		assertThat(historyRows()).isEqualTo(before);
		assertThat(jdbc.sql("SELECT count(*) FROM accounts.flyway_schema_history WHERE script LIKE '%promote_bootstrap_staff%'")
			.query(Integer.class).single()).isZero();
	}

	@Test
	void bootstrapEmailIsValidated() {
		assertThat(AccountsConfig.bootstrapStaffEmail(null)).isEmpty();
		assertThat(AccountsConfig.bootstrapStaffEmail("   ")).isEmpty();
		assertThat(AccountsConfig.bootstrapStaffEmail("  Staff@Example.COM ")).isEqualTo("staff@example.com");
		for (String bad : new String[] { "o'brien@example.com", "x' OR '1'='1", "no-at-sign", "a b@example.com",
				"@example.com", "staff@", "a@b@c" }) {
			assertThatThrownBy(() -> AccountsConfig.bootstrapStaffEmail(bad))
				.as(bad)
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("MUSICBOXD_BOOTSTRAP_STAFF_EMAIL");
		}
	}

	private void migrateWith(String email) {
		ModuleFlyway.forSchema(dataSource, "accounts",
				Map.of(AccountsConfig.STAFF_EMAIL_PLACEHOLDER, AccountsConfig.bootstrapStaffEmail(email)))
			.migrate();
	}

	private UUID insertAccount(String email) {
		UUID id = UUID.randomUUID();
		jdbc.sql("INSERT INTO accounts.accounts (id, email, password_hash, email_verified_at) VALUES (:id, :email, '{noop}x', now())")
			.param("id", id).param("email", email).update();
		return id;
	}

	private String role(UUID id) {
		return jdbc.sql("SELECT role FROM accounts.accounts WHERE id = :id").param("id", id).query(String.class).single();
	}

	private Integer historyRows() {
		return jdbc.sql("SELECT count(*) FROM accounts.flyway_schema_history").query(Integer.class).single();
	}

	private Integer staffCount() {
		return jdbc.sql("SELECT count(*) FROM accounts.accounts WHERE role = 'STAFF'").query(Integer.class).single();
	}
}
