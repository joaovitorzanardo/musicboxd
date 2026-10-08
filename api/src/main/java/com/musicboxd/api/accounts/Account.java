package com.musicboxd.api.accounts;

import java.util.UUID;

record Account(UUID id, String email, String passwordHash) {

	@Override
	public String toString() {
		return "Account[id=" + id + ", email=" + email + ", passwordHash=***]";
	}
}
