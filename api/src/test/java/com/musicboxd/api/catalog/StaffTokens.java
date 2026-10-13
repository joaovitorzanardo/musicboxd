package com.musicboxd.api.catalog;

import java.util.UUID;

import com.musicboxd.api.accounts.Role;
import com.musicboxd.api.accounts.TokenService;

final class StaffTokens {

	private StaffTokens() {
	}

	static String bearer(TokenService tokens, Role role) {
		return "Bearer " + tokens.issue(UUID.randomUUID(), role).value();
	}
}
