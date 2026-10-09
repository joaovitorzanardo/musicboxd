package com.musicboxd.api.accounts;

/**
 * The per-account rate-limit key for anonymous auth endpoints (MBD-23): the email as the account
 * stores it, hashed so every key has the same size and no address sits in the limiter's memory.
 */
final class EmailRateLimitKey {

	private EmailRateLimitKey() {
	}

	static String of(String email) {
		return "email:" + OpaqueTokens.hash(AccountService.normalize(email));
	}
}
