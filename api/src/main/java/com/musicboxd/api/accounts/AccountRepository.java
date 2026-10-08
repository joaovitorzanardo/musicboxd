package com.musicboxd.api.accounts;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class AccountRepository {

	static final String EMAIL_UNIQUE = "accounts_email_key";

	private final JdbcClient jdbc;

	AccountRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(Account account) {
		jdbc.sql("INSERT INTO accounts.accounts (id, email, password_hash) VALUES (:id, :email, :hash)")
			.param("id", account.id())
			.param("email", account.email())
			.param("hash", account.passwordHash())
			.update();
	}

	Optional<Account> findByEmail(String email) {
		return jdbc.sql("SELECT id, email, password_hash FROM accounts.accounts WHERE lower(email) = lower(:email)")
			.param("email", email)
			.query(Account.class)
			.optional();
	}

	Optional<Account> findById(UUID id) {
		return jdbc.sql("SELECT id, email, password_hash FROM accounts.accounts WHERE id = :id")
			.param("id", id)
			.query(Account.class)
			.optional();
	}
}
