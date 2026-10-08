package com.musicboxd.api.profiles;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Package-private: other modules go through {@link ProfilesApi} (AD-1). */
@Repository
class ProfileRepository {

	static final String USERNAME_UNIQUE = "profiles_username_key";

	private final JdbcClient jdbc;

	ProfileRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(UUID id, String username) {
		jdbc.sql("INSERT INTO profiles.profiles (id, username) VALUES (:id, :username)")
			.param("id", id)
			.param("username", username)
			.update();
	}

	Optional<String> findUsername(UUID id) {
		return jdbc.sql("SELECT username FROM profiles.profiles WHERE id = :id")
			.param("id", id)
			.query(String.class)
			.optional();
	}
}
