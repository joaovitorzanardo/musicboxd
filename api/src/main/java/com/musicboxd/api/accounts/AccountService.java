package com.musicboxd.api.accounts;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.musicboxd.api.db.Constraints;
import com.musicboxd.api.profiles.ProfilesApi;

@Service
public class AccountService {

	/** BCrypt reads only 72 bytes, and Spring Security rejects longer input with an exception. */
	static final int MAX_PASSWORD_BYTES = 72;

	private final AccountRepository accounts;
	private final ProfilesApi profiles;
	private final PasswordEncoder passwordEncoder;
	/** Checked when the email is unknown, so a miss costs the same hash as a wrong password. */
	private final String dummyHash;

	AccountService(AccountRepository accounts, ProfilesApi profiles, PasswordEncoder passwordEncoder) {
		this.accounts = accounts;
		this.profiles = profiles;
		this.passwordEncoder = passwordEncoder;
		this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	/** Creates the account and its profile in one transaction: both or neither. */
	@Transactional
	public AccountView register(String email, String password, String username) {
		if (tooLong(password)) {
			throw new PasswordTooLongException();
		}
		var account = new Account(UUID.randomUUID(), normalize(email), passwordEncoder.encode(password));
		try {
			accounts.insert(account);
		}
		catch (DuplicateKeyException e) {
			if (Constraints.violated(e, AccountRepository.EMAIL_UNIQUE)) {
				throw new EmailTakenException();
			}
			throw e;
		}
		profiles.createProfile(account.id(), username);
		return new AccountView(account.id(), account.email(), username);
	}

	/** Returns the account id, or throws {@link InvalidCredentialsException} without saying why. */
	@Transactional(readOnly = true)
	public UUID authenticate(String email, String password) {
		if (tooLong(password)) {
			throw new InvalidCredentialsException();
		}
		var account = accounts.findByEmail(normalize(email));
		String hash = account.map(Account::passwordHash).orElse(dummyHash);
		if (!passwordEncoder.matches(password, hash) || account.isEmpty()) {
			throw new InvalidCredentialsException();
		}
		return account.get().id();
	}

	@Transactional(readOnly = true)
	public AccountView describe(UUID accountId) {
		var account = accounts.findById(accountId).orElseThrow(AccountNotFoundException::new);
		String username = profiles.findUsername(accountId).orElseThrow(AccountNotFoundException::new);
		return new AccountView(accountId, account.email(), username);
	}

	private static String normalize(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	private static boolean tooLong(String password) {
		return password.getBytes(UTF_8).length > MAX_PASSWORD_BYTES;
	}
}
