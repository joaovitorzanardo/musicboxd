package com.musicboxd.api.profiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import com.musicboxd.api.TestcontainersConfiguration;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ProfilesApiTest {

	@Autowired
	private ProfilesApi profiles;

	@Test
	void createdProfileUsernameIsReadableBack() {
		var id = UUID.randomUUID();
		String username = uniqueUsername();
		profiles.createProfile(id, username);
		assertThat(profiles.findUsername(id)).contains(username);
	}

	@Test
	void unknownAccountHasNoUsername() {
		assertThat(profiles.findUsername(UUID.randomUUID())).isEmpty();
	}

	@Test
	void usernameUniquenessIgnoresCase() {
		String username = uniqueUsername();
		profiles.createProfile(UUID.randomUUID(), username);
		assertThatThrownBy(() -> profiles.createProfile(UUID.randomUUID(), username.toUpperCase()))
			.isInstanceOf(UsernameTakenException.class);
	}

	@Test
	void sameAccountTwiceIsNotReportedAsUsernameTaken() {
		var id = UUID.randomUUID();
		profiles.createProfile(id, uniqueUsername());
		// Primary-key clash is a programming error, not a 409 for the user.
		assertThatThrownBy(() -> profiles.createProfile(id, uniqueUsername()))
			.isInstanceOf(DuplicateKeyException.class)
			.isNotInstanceOf(UsernameTakenException.class);
	}

	@Test
	void databaseRejectsUsernameOutsideTheAllowedShape() {
		// The HTTP edge validates first (Task 4); the CHECK is the backstop.
		assertThatThrownBy(() -> profiles.createProfile(UUID.randomUUID(), "has space"))
			.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> profiles.createProfile(UUID.randomUUID(), "ab"))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	private static String uniqueUsername() {
		return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
