package com.musicboxd.api.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.musicboxd.api.TestcontainersConfiguration;
import com.musicboxd.api.profiles.ProfilesApi;
import com.musicboxd.api.profiles.UsernameTakenException;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountServiceTest {

	private static final String PASSWORD = "correct-horse";

	@Autowired
	private AccountService accounts;

	@Autowired
	private ProfilesApi profiles;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void registrationStoresABcryptHashAndPutsTheUsernameInProfiles() {
		String email = uniqueEmail();
		String username = uniqueUsername();
		AccountView view = accounts.register(email, PASSWORD, username);

		assertThat(view.email()).isEqualTo(email);
		assertThat(view.username()).isEqualTo(username);
		String hash = jdbc.sql("SELECT password_hash FROM accounts.accounts WHERE id = :id")
			.param("id", view.id()).query(String.class).single();
		assertThat(hash).startsWith("{bcrypt}").doesNotContain(PASSWORD);
		assertThat(profiles.findUsername(view.id())).contains(username);
	}

	@Test
	void accountsSchemaHasNoUsernameColumn() {
		Integer columns = jdbc.sql("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'accounts' AND column_name = 'username'""")
			.query(Integer.class).single();
		assertThat(columns).isZero();
	}

	@Test
	void emailIsTrimmedAndLowercased() {
		String email = uniqueEmail();
		AccountView view = accounts.register("  " + email.toUpperCase() + " ", PASSWORD, uniqueUsername());
		assertThat(view.email()).isEqualTo(email);
		assertThat(accounts.authenticate(email.toUpperCase(), PASSWORD)).isEqualTo(view.id());
	}

	@Test
	void duplicateEmailDifferingOnlyInCaseIsRejected() {
		String email = uniqueEmail();
		accounts.register(email, PASSWORD, uniqueUsername());
		assertThatThrownBy(() -> accounts.register(email.toUpperCase(), PASSWORD, uniqueUsername()))
			.isInstanceOf(EmailTakenException.class);
	}

	@Test
	void takenUsernameRollsBackTheAccount() {
		String username = uniqueUsername();
		accounts.register(uniqueEmail(), PASSWORD, username);

		String email = uniqueEmail();
		assertThatThrownBy(() -> accounts.register(email, PASSWORD, username.toUpperCase()))
			.isInstanceOf(UsernameTakenException.class);
		Integer orphans = jdbc.sql("SELECT count(*) FROM accounts.accounts WHERE email = :email")
			.param("email", email).query(Integer.class).single();
		assertThat(orphans).isZero();
		// The email is still free for a retry with another username.
		assertThat(accounts.register(email, PASSWORD, uniqueUsername()).email()).isEqualTo(email);
	}

	@Test
	void passwordOverBcryptByteLimitIsRejectedEvenWhenUnder72Characters() {
		String twoByteChars = "é".repeat(37); // 37 chars, 74 UTF-8 bytes
		assertThatThrownBy(() -> accounts.register(uniqueEmail(), twoByteChars, uniqueUsername()))
			.isInstanceOf(PasswordTooLongException.class);
	}

	@Test
	void correctPasswordAuthenticates() {
		String email = uniqueEmail();
		AccountView view = accounts.register(email, PASSWORD, uniqueUsername());
		assertThat(accounts.authenticate(email, PASSWORD)).isEqualTo(view.id());
	}

	@Test
	void wrongPasswordUnknownEmailAndOverlongPasswordAllFailTheSameWay() {
		String email = uniqueEmail();
		accounts.register(email, PASSWORD, uniqueUsername());
		assertThatThrownBy(() -> accounts.authenticate(email, "wrong-password"))
			.isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> accounts.authenticate(uniqueEmail(), PASSWORD))
			.isInstanceOf(InvalidCredentialsException.class);
		assertThatThrownBy(() -> accounts.authenticate(email, "x".repeat(73)))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void describeJoinsEmailFromAccountsAndUsernameFromProfiles() {
		String email = uniqueEmail();
		String username = uniqueUsername();
		AccountView registered = accounts.register(email, PASSWORD, username);
		assertThat(accounts.describe(registered.id())).isEqualTo(registered);
	}

	@Test
	void describeUnknownAccountIsNotFound() {
		assertThatThrownBy(() -> accounts.describe(UUID.randomUUID()))
			.isInstanceOf(AccountNotFoundException.class);
	}

	static String uniqueEmail() {
		return "u" + UUID.randomUUID().toString().replace("-", "") + "@example.com";
	}

	static String uniqueUsername() {
		return "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
	}
}
