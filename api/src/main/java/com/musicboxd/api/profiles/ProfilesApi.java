package com.musicboxd.api.profiles;

import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.musicboxd.api.db.Constraints;

/**
 * The profiles module's public API (AD-1). Owns display identity, starting with the
 * username (AD-13); other modules never touch the profiles schema directly.
 */
@Service
public class ProfilesApi {

	private final ProfileRepository profiles;

	ProfilesApi(ProfileRepository profiles) {
		this.profiles = profiles;
	}

	/** Joins the caller's transaction, so registration creates account and profile atomically. */
	@Transactional
	public void createProfile(UUID accountId, String username) {
		try {
			profiles.insert(accountId, username);
		}
		catch (DuplicateKeyException e) {
			if (Constraints.violated(e, ProfileRepository.USERNAME_UNIQUE)) {
				throw new UsernameTakenException();
			}
			throw e;
		}
	}

	@Transactional(readOnly = true)
	public Optional<String> findUsername(UUID accountId) {
		return profiles.findUsername(accountId);
	}
}
